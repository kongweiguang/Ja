// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TurnReviewTarget } from "@/features/workbench/review";

export type InitialReviewSourceDecision =
  | { readonly kind: "preserve" }
  | { readonly kind: "uncommitted" };

/** 菜单只提供具有实际冻结内容的终态，不制造不可操作的历史入口。 */
export function canOpenTurnReview(
  target: TurnReviewTarget | undefined,
): target is TurnReviewTarget {
  return target !== undefined && target.stats.files > 0 && target.artifactId.length > 0;
}

/**
 * 每个 runtime/Workspace/Thread 首次都进入 Git 未提交；之后 preserve 用户显式选择，
 * 新 Turn 到达只更新菜单事实，不能抢走正在阅读的 Git 或历史范围。
 */
export function decideInitialReviewSource(
  selectedScope: string | undefined,
  currentScope: string,
): InitialReviewSourceDecision {
  if (selectedScope === currentScope) return { kind: "preserve" };
  return { kind: "uncommitted" };
}

/**
 * 已打开历史目标的标题由打开动作冻结为“第 N 轮修改”；后续 Turn 只改变独立的最后一轮入口。
 */
export function resolveRetainedTurnLabel(
  opened: TurnReviewTarget | undefined,
  recordedLabel: string,
  latest: TurnReviewTarget | undefined,
): string {
  if (opened !== undefined && recordedLabel === "最后一轮" && opened.turnId !== latest?.turnId) {
    return opened.turnNumber === undefined ? "所选轮次修改" : `第 ${opened.turnNumber} 轮修改`;
  }
  return recordedLabel;
}
