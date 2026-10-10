// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { PlanStatusBar, type PlanReadModel } from "@/features/plans";
import { planModel } from "./planFixtures";

/** 主会话和侧聊重连后使用相同的 Plan 投影。 */
function standalonePlan(status: PlanReadModel["plan"]["status"]): PlanReadModel {
  return planModel(status);
}

describe("PlanStatusBar", () => {
  it("shows progress and run controls", async () => {
    const user = userEvent.setup();
    const onPause = vi.fn();
    const onStop = vi.fn();
    const onOpen = vi.fn();
    render(
      <PlanStatusBar
        planModel={standalonePlan("executing")}
        onOpen={onOpen}
        onPause={onPause}
        onStop={onStop}
      />,
    );

    expect(screen.getByText("实现界面 / 执行中")).toBeTruthy();
    expect(
      screen.getByRole("progressbar", { name: "计划必要步骤进度" }).getAttribute("aria-valuenow"),
    ).toBe("1");
    await user.click(screen.getByRole("button", { name: "暂停计划" }));
    await user.click(screen.getByRole("button", { name: "停止计划" }));
    expect(onPause).toHaveBeenCalledOnce();
    expect(onStop).toHaveBeenCalledOnce();
  });

  it("routes a waiting Plan to the answer workflow", async () => {
    const user = userEvent.setup();
    const onAnswerQuestion = vi.fn();
    render(
      <PlanStatusBar
        planModel={standalonePlan("paused")}
        onOpen={vi.fn()}
        hasPendingQuestion
        onResume={vi.fn()}
        onPause={vi.fn()}
        onAnswerQuestion={onAnswerQuestion}
        onStop={vi.fn()}
      />,
    );

    expect(screen.getByText("等待回答")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "继续计划" })).toBeNull();
    await user.click(screen.getByRole("button", { name: "回答问题" }));
    expect(onAnswerQuestion).toHaveBeenCalledOnce();
  });
});
