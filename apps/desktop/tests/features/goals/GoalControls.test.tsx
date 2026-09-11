// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ComposerGoalStatus,
  GoalActivityCard,
  GoalStatusBar,
  PlanTimelineBlock,
  goalPrimaryAction,
} from "@/features/goals";
import { goalModel } from "./goalFixtures";

describe("Goal composer controls", () => {
  afterEach(cleanup);

  /** 独立 Plan 没有 Goal 时仍可打开真实编辑面板，不能把两者的入口条件绑定。 */
  it("opens an existing standalone Plan without creating a Goal", async () => {
    const openPlan = vi.fn();
    const openGoal = vi.fn();
    const user = userEvent.setup();
    render(<ComposerGoalStatus mode="plan" onOpenPlan={openPlan} onOpenGoal={openGoal} />);
    await user.click(screen.getByRole("button", { name: "计划状态：已开启" }));
    await user.click(screen.getByRole("button", { name: "查看计划" }));
    expect(openPlan).toHaveBeenCalledOnce();
    expect(openGoal).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("hides the default mode and returns focus after closing the Plan status popover", async () => {
    const disablePlan = vi.fn();
    const user = userEvent.setup();
    const { container, rerender } = render(<ComposerGoalStatus mode="default" />);

    expect(container).toBeEmptyDOMElement();
    rerender(<ComposerGoalStatus mode="plan" onDisablePlan={disablePlan} />);
    const trigger = screen.getByRole("button", { name: "计划状态：已开启" });
    await user.click(trigger);
    expect(screen.getByRole("dialog", { name: "计划详情" })).toBeVisible();
    await user.keyboard("{Escape}");
    expect(trigger).toHaveFocus();

    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "关闭计划模式" }));
    expect(disablePlan).toHaveBeenCalledOnce();
  });

  it("shows only the Goal entry when Goal and Plan mode coexist", async () => {
    const openGoal = vi.fn();
    const disablePlan = vi.fn();
    const user = userEvent.setup();
    render(
      <ComposerGoalStatus
        mode="plan"
        goal={goalModel("working").goal}
        currentStepTitle="实现界面"
        onOpenGoal={openGoal}
        onDisablePlan={disablePlan}
      />,
    );

    expect(screen.getByRole("button", { name: "目标状态：执行中" })).toHaveTextContent("目标");
    expect(screen.queryByText(/^计划$/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "目标状态：执行中" }));
    expect(screen.getByRole("button", { name: "关闭计划模式" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "查看目标" }));
    expect(openGoal).toHaveBeenCalledOnce();
  });

  /** Goal-only 没有必要步骤时不暴露 max=0 的伪进度语义，状态与详情入口仍保持可用。 */
  it("omits progress semantics when a Goal has no required steps", async () => {
    const user = userEvent.setup();
    const base = goalModel().goal;
    const goalOnly = {
      ...base,
      activePlanId: null,
      activePlanRevisionId: null,
      activePlanHash: null,
      currentStepId: null,
      completedRequiredSteps: 0,
      totalRequiredSteps: 0,
    };
    const { rerender } = render(
      <ComposerGoalStatus mode="default" goal={goalOnly} onOpenGoal={vi.fn()} />,
    );

    await user.click(screen.getByRole("button", { name: "目标状态：执行中" }));
    expect(screen.queryByRole("progressbar", { name: "目标进度" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "查看目标" })).toBeVisible();

    rerender(<GoalStatusBar goal={goalOnly} onOpen={vi.fn()} onPause={vi.fn()} />);
    expect(screen.queryByRole("progressbar", { name: "目标进度" })).not.toBeInTheDocument();
    expect(screen.queryByText("0/0")).not.toBeInTheDocument();
  });

  it("shows exactly one phase action and expands the goal", async () => {
    const user = userEvent.setup();
    const pause = vi.fn();
    const open = vi.fn();
    render(
      <GoalStatusBar
        goal={goalModel().goal}
        currentStepTitle="实现界面"
        onOpen={open}
        onPause={pause}
      />,
    );

    expect(screen.getByRole("button", { name: "暂停目标" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "批准" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /交付生产级 Plan 与 Goal/u }));
    expect(open).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "暂停目标" }));
    expect(pause).toHaveBeenCalledTimes(1);
  });

  it("derives recovery actions exhaustively and hides terminal goals", () => {
    expect(goalPrimaryAction(goalModel("working").goal)).toBe("pause");
    const notMet = goalModel("needs_attention").evaluation;
    expect(goalPrimaryAction(goalModel("working").goal, notMet)).toBe("continue");
    expect(
      goalPrimaryAction(goalModel("working").goal, {
        ...notMet!,
        planRevisionId: "planrev_stale",
      }),
    ).toBe("pause");
    expect(goalPrimaryAction(goalModel("paused").goal)).toBe("resume");
    expect(goalPrimaryAction(goalModel("waiting_input").goal)).toBe("resolve");
    expect(goalPrimaryAction(goalModel("needs_attention").goal)).toBe("continue");
    const achieved = {
      ...goalModel().goal,
      status: "achieved" as const,
      phase: "achieved" as const,
    };
    const { container } = render(<GoalStatusBar goal={achieved} onOpen={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("offers one real continuation action when evaluation is not met", async () => {
    const user = userEvent.setup();
    const continueGoal = vi.fn();
    render(
      <GoalStatusBar
        goal={goalModel("working").goal}
        evaluation={goalModel("needs_attention").evaluation}
        onOpen={vi.fn()}
        onContinue={continueGoal}
      />,
    );

    expect(screen.queryByRole("button", { name: "恢复目标" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "继续处理" }));
    expect(continueGoal).toHaveBeenCalledOnce();
  });

  it("renders a terminal goal as a non-interactive timeline fact", () => {
    render(
      <GoalActivityCard
        activity={{
          goalId: "goal_done",
          objective: "完成生产验收",
          status: "achieved",
          goalRevision: 8,
          eventSequence: 21,
          occurredAt: "2026-09-05T00:00:00Z",
        }}
      />,
    );

    expect(screen.getByLabelText("完成生产验收，已达成")).toBeVisible();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("renders the structured Plan in Timeline and opens details only on demand", async () => {
    const open = vi.fn();
    const user = userEvent.setup();
    render(<PlanTimelineBlock model={goalModel("working")} onOpenDetails={open} />);

    const block = screen.getByLabelText(/计划版本 2/u);
    expect(block).toHaveAttribute("data-goal-ui", "plan-timeline");
    expect(screen.getByText("冻结契约")).toBeVisible();
    expect(screen.getByText("实现界面")).toBeVisible();
    expect(screen.getByText("1/1 项验收")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "查看详情" }));
    expect(open).toHaveBeenCalledOnce();
  });
});
