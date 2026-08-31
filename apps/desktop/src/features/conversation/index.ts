// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export { useConversationController } from "./application/useConversationController";
export type { ConversationController } from "./application/useConversationController";
export { useConversationInteractionController } from "./application/useConversationInteractionController";
export type { ConversationInteractionOptions } from "./application/useConversationInteractionController";
export type {
  ConversationArtifactPort,
  ConversationAcceptedTurn,
  ConversationAttachmentPort,
  ConversationCancelResult,
  ConversationHostEvent,
  ConversationPreferencesPort,
  ConversationQueuedInputResult,
  ConversationTurnPort,
} from "./application/ports";
export { Composer, ComposerContext } from "./ui/composer/Composer";
export { resolveContextUsage } from "./domain/contextUsage";
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
