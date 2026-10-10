// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ComposerPlanStatus, PlanTimelineBlock } from "@/features/plans";
import { planModel } from "./planFixtures";

describe("Plan controls", () => {
  afterEach(cleanup);

  it("opens the persisted Plan from Composer", async () => {
    const openPlan = vi.fn();
    const user = userEvent.setup();
    render(<ComposerPlanStatus mode="plan" onOpenPlan={openPlan} />);
    await user.click(screen.getByRole("button", { name: "计划状态：已开启" }));
    await user.click(screen.getByRole("button", { name: "查看计划" }));
    expect(openPlan).toHaveBeenCalledOnce();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("occupies no space in default mode and returns focus after closing", async () => {
    const user = userEvent.setup();
    const { container, rerender } = render(<ComposerPlanStatus mode="default" />);
    expect(container).toBeEmptyDOMElement();
    rerender(<ComposerPlanStatus mode="plan" onDisablePlan={vi.fn()} />);
    const trigger = screen.getByRole("button", { name: "计划状态：已开启" });
    await user.click(trigger);
    expect(screen.getByRole("dialog", { name: "计划详情" })).toBeVisible();
    await user.keyboard("{Escape}");
    expect(trigger).toHaveFocus();
  });

  it("shows the current Plan and opens full details only on demand", async () => {
    const open = vi.fn();
    const user = userEvent.setup();
    render(<PlanTimelineBlock planModel={planModel()} onOpenDetails={open} />);
    expect(screen.getByText("冻结契约")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "查看详情" }));
    expect(open).toHaveBeenCalledOnce();
  });
});
