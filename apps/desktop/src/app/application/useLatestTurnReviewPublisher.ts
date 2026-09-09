// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useEffect, useRef } from "react";
import type { TurnReviewTarget } from "@/features/workbench/review";

export interface LatestTurnReviewPublisherOptions {
  readonly workspaceId: string | undefined;
  readonly threadId: string;
  readonly target: TurnReviewTarget | undefined;
  readonly publish: (target: TurnReviewTarget | undefined) => void;
}

/**
 * 发布最近终态 Turn 的冻结记录；新终态只更新“最后一轮”入口，不能替换正在阅读的历史目标。
 * Thread、Workspace 切换或组件卸载时撤销旧身份，ref 保证 cleanup 调用最新壳层函数。
 */
export function useLatestTurnReviewPublisher({
  workspaceId,
  threadId,
  target,
  publish,
}: LatestTurnReviewPublisherOptions): void {
  const publishRef = useRef(publish);

  useEffect(() => {
    publishRef.current = publish;
  }, [publish]);

  useEffect(() => {
    publish(target);
  }, [publish, target]);

  useEffect(
    () => () => {
      publishRef.current(undefined);
    },
    [threadId, workspaceId],
  );
}
