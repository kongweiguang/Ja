// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  canOpenTurnReview,
  decideInitialReviewSource,
  resolveRetainedTurnLabel,
} from "@/app/application/reviewSourceSelection";
import type { TurnReviewTarget } from "@/features/workbench/review";

const FROZEN_TARGET: TurnReviewTarget = {
  kind: "frozen_turn",
  workspaceId: "ws_one",
  threadId: "thr_one",
  turnId: "turn_one",
  threadRevision: 3,
  state: "complete",
  incompleteReasons: [],
  files: [],
  stats: { files: 1, additions: 2, deletions: 1, binaryFiles: 0, truncated: false },
  artifactId: "artifact_one",
};

describe("reviewSourceSelection", () => {
  it("首次进入始终选择 Git 未提交，最后一轮只作为次要菜单入口", () => {
    expect(decideInitialReviewSource(undefined, "scope_one")).toEqual({
      kind: "uncommitted",
    });
    expect(
      canOpenTurnReview({ ...FROZEN_TARGET, stats: { ...FROZEN_TARGET.stats, files: 0 } }),
    ).toBe(false);
  });

  it("同一作用域已有显式选择时保持原范围，不被新 Turn 抢回", () => {
    expect(decideInitialReviewSource("scope_one", "scope_one")).toEqual({
      kind: "preserve",
    });
  });

  it("新 Turn 到达后保持历史轮次精确标题，不冒充最后一轮", () => {
    const nextTurn = { ...FROZEN_TARGET, turnId: "turn_two", artifactId: "artifact_two" };
    expect(resolveRetainedTurnLabel(FROZEN_TARGET, "最后一轮", FROZEN_TARGET)).toBe("最后一轮");
    expect(resolveRetainedTurnLabel(FROZEN_TARGET, "第 2 轮修改", nextTurn)).toBe("第 2 轮修改");
  });
});
