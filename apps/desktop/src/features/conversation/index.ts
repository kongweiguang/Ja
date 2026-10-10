// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export { useConversationController } from "./application/useConversationController";
export { readFullItemContent } from "./application/readFullMessageContent";
export { readFullAnswerContent } from "./application/readFullMessageContent";
export {
  conversationMarkdownFilename,
  exportConversationMarkdown,
} from "./application/exportConversationMarkdown";
export type { ConversationController } from "./application/useConversationController";
export { useConversationInteractionController } from "./application/useConversationInteractionController";
export type {
  ConversationProcessBinding,
  ConversationProcessPort,
} from "./application/processSessionTypes";
export { useInteractionController } from "./application/useInteractionController";
export { useClarificationController } from "./application/useInteractionController";
export type {
  InteractionController,
  InteractionControllerOptions,
} from "./application/useInteractionController";
export { InteractionCard } from "./ui/interaction/InteractionCard";
export {
  publishInteractionHostEvent,
  subscribeInteractionHostEvents,
} from "./application/interactionEventBus";
export { createInteractionPort } from "./application/interactionAdapterPort";
export type {
  InteractionAnswer,
  InteractionEvent,
  InteractionOption,
  InteractionPort,
  InteractionQuestion,
  InteractionQuestionKind,
  InteractionRequest,
  InteractionRevisionConflict,
  InteractionSnapshot,
} from "./application/interactionPort";
export type {
  ConversationInteractionController,
  ConversationInteractionOptions,
  ConversationQueuedInputView,
} from "./application/useConversationInteractionController";
export type {
  ConversationAccessMode,
  ConversationCollaborationMode,
  ConversationModelOption,
  ConversationModelSelection,
  ConversationThreadPreferences,
  ConversationUsageReader,
  ConversationUsageSummary,
  ReasoningLevel,
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
export { downloadPublicMarkdown } from "./ui/timeline/exportPublicText";
export type { ComposerSubmit, ComposerNativeDropEvent } from "./ui/composer/Composer";
export type {
  ComposerSlashCommand,
  ComposerSlashCommandContext,
  ComposerSlashInvocation,
  ComposerWorkspaceSearchResult,
} from "./ui/composer/composerSuggestions";
export { resolveContextUsage } from "./domain/contextUsage";
export { countCommittedConversationActivity } from "./domain/conversationActivity";
export type { ConversationActivityCounts } from "./domain/conversationActivity";
export { projectAnsweredInteractionResult } from "./application/interactionTimeline";
export type { ConversationContextReference, UserContentBlock } from "./domain/userContent";
export { ConversationSummaryPopover } from "./ui/summary/ConversationSummaryPopover";
export { ThreadOperationsMenu } from "./ui/thread-actions/ThreadOperationsMenu";
export type {
  ConversationMcpReader,
  ConversationMcpServerStatus,
  ConversationMcpStatusSnapshot,
  ConversationSummary,
} from "./ui/summary/ConversationSummaryPopover";
export { ReplyFileOpenMenu } from "./ui/reply-files/ReplyFileOpenMenu";
export { useReplyFileOpen } from "./application/useReplyFileOpen";
export { ChatTimeline } from "./ui/timeline/ChatTimeline";
export { ConversationTimelineSurface } from "./ui/timeline/ConversationTimelineSurface";
export { TurnChangesCard } from "./ui/timeline/TurnChangesCard";
export { exportPublicMarkdown } from "./ui/timeline/exportPublicText";
export type { HistoryAttachmentThumbnailPort } from "./ui/timeline/HistoryAttachmentThumbnail";
export { TimelineScrollCache } from "./ui/timeline/timelineScroll";
export { TimelineDisclosureCache } from "./ui/timeline/timelineDisclosure";
export {
  selectApprovalClosedAtForThread,
  selectApprovalDecisionsForThread,
  selectApprovalsForThread,
  selectCommittedItemsForThread,
  selectItemsForThread,
  selectTurnsForThread,
  selectTaskActivitiesForRoot,
  useTimelineStore,
} from "./application/timelineStore";
export type { TimelineRuntimeFence, TimelineStore } from "./application/timelineStore";
export {
  isWorkItem,
  itemChangedFiles,
  itemDiffStat,
  turnDurationMs,
  turnStatusLabel,
} from "./domain/timelineTypes";
export type {
  TimelineApproval,
  TimelineItemAdapter,
  TimelineTurn,
  TurnChangeSet,
} from "./domain/timelineTypes";
export type {
  AttachmentSummary,
  TimelineEvent,
  TimelineSnapshot,
  TimelineTaskActivityEntry,
} from "./domain/timelineContracts";
