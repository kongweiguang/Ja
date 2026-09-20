// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { memo, useMemo, type ComponentProps, type ReactElement } from "react";
import { useShallow } from "zustand/react/shallow";
import {
  ChatTimeline,
  projectAnsweredInteractionResult,
  selectItemsForThread,
  useTimelineStore,
  type InteractionController,
} from "@/features/conversation";

interface ConversationTimelineSurfaceProps
  extends Omit<ComponentProps<typeof ChatTimeline>, "items">,
    Pick<InteractionController, "answeredRequest" | "answeredAnswers"> {}

/**
 * 将逐段正文订阅限制在 Timeline 子树内；Composer 与导航不读取 draft item，因此流式 delta
 * 不应让它们参与 React commit。回答 ACK 仍在同一边界投影，直到权威 ToolResult 原位接管。
 */
export const ConversationTimelineSurface = memo(function ConversationTimelineSurface({
  threadId,
  answeredRequest,
  answeredAnswers,
  ...timelineProps
}: ConversationTimelineSurfaceProps): ReactElement {
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
