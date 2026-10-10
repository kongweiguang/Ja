// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { memo, useMemo, type ComponentProps, type ReactElement } from "react";
import { useShallow } from "zustand/react/shallow";
import { projectAnsweredInteractionResult } from "../../application/interactionTimeline";
import type { InteractionController } from "../../application/useInteractionController";
import { selectItemsForThread, useTimelineStore } from "../../application/timelineStore";
import { ChatTimeline } from "./ChatTimeline";

interface ConversationTimelineSurfaceProps
  extends Omit<ComponentProps<typeof ChatTimeline>, "items">,
    Pick<InteractionController, "answeredRequest" | "answeredAnswers"> {
  /** Hidden retained Threads must not subscribe to streaming Timeline state. */
  active?: boolean;
}

/** Keeps high-frequency draft subscriptions below the sibling Composer and projects answered ACKs in one place. */
const ActiveConversationTimelineSurface = memo(function ActiveConversationTimelineSurface({
  threadId,
  answeredRequest,
  answeredAnswers,
  ...timelineProps
}: Omit<ConversationTimelineSurfaceProps, "active">): ReactElement {
  const selectedThreadId = threadId ?? "";
  const items = useTimelineStore(
    useShallow((state) =>
      selectedThreadId === "" ? [] : selectItemsForThread(selectedThreadId)(state),
    ),
  );
  const timelineItems = useMemo(
    () =>
      projectAnsweredInteractionResult(
        items,
        answeredRequest?.threadId === selectedThreadId ? answeredRequest : null,
        answeredRequest?.threadId === selectedThreadId ? answeredAnswers : {},
      ),
    [answeredAnswers, answeredRequest, items, selectedThreadId],
  );

  return <ChatTimeline {...timelineProps} threadId={threadId} items={timelineItems} />;
});

/** Unmounts the Timeline subscriber when its retained Thread is hidden, without dropping outer host state. */
export const ConversationTimelineSurface = memo(function ConversationTimelineSurface({
  active = true,
  ...props
}: ConversationTimelineSurfaceProps): ReactElement | null {
  return active ? <ActiveConversationTimelineSurface {...props} /> : null;
});
