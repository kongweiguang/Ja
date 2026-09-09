// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export { useConversationController } from "./application/useConversationController";
export type { ConversationController } from "./application/useConversationController";
export { useConversationInteractionController } from "./application/useConversationInteractionController";
export type {
  ConversationInteractionOptions,
  ConversationQueuedInputView,
} from "./application/useConversationInteractionController";
export type {
  ConversationArtifactPort,
  ConversationAcceptedTurn,
  ConversationAttachment,
  ConversationAttachmentDraftItem,
  ConversationAttachmentImportEvent,
  ConversationAttachmentPort,
  ConversationCancelResult,
  ConversationHostEvent,
  ConversationPlanCreationPort,
  ConversationPreferencesPort,
  ConversationInputQueueMutationResult,
  ConversationTurnPort,
} from "./application/ports";
export { Composer } from "./ui/composer/Composer";
export type { ComposerSubmit } from "./ui/composer/Composer";
export type {
  ComposerSlashCommand,
  ComposerSlashCommandContext,
  ComposerSlashInvocation,
  ComposerWorkspaceSearchResult,
} from "./ui/composer/composerSuggestions";
export { resolveContextUsage } from "./domain/contextUsage";
export type { ConversationContextReference, UserContentBlock } from "./domain/userContent";
export { ConversationSummaryPopover } from "./ui/summary/ConversationSummaryPopover";
export { ThreadOperationsMenu } from "./ui/thread-actions/ThreadOperationsMenu";
export type { ConversationSummary } from "./ui/summary/ConversationSummaryPopover";
export { ReplyFileOpenMenu } from "./ui/reply-files/ReplyFileOpenMenu";
export { useReplyFileOpen } from "./application/useReplyFileOpen";
export { ChatTimeline } from "./ui/timeline/ChatTimeline";
export {
  selectApprovalClosedAt,
  selectApprovalDecisions,
  selectApprovals,
  selectItemsForThread,
  selectGoalActivitiesForOwner,
  selectTaskActivitiesForRoot,
  useTimelineStore,
} from "./application/timelineStore";
export type { TimelineStore } from "./application/timelineStore";
export {
  isWorkItem,
  itemChangedFiles,
  itemDiffStat,
  turnDurationMs,
  turnStatusLabel,
} from "./domain/timelineTypes";
export type { TimelineApproval, TimelineItemAdapter, TimelineTurn } from "./domain/timelineTypes";
export type { AttachmentSummary, TimelineGoalActivity } from "./domain/timelineContracts";
