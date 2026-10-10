// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PlanWorkbench, revisionDiff } from "@/features/plans";
import { evidence, planModel, planRevision } from "./planFixtures";

describe("PlanWorkbench", () => {
  afterEach(cleanup);

  it("shows the empty state and a retry action only for a real read error", async () => {
    const retry = vi.fn();
    const user = userEvent.setup();
    const { rerender } = render(<PlanWorkbench />);
    expect(screen.getByRole("status")).toHaveTextContent("当前会话没有计划");
    rerender(<PlanWorkbench error="读取失败" onRetry={retry} />);
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(retry).toHaveBeenCalledOnce();
  });

  it("renders the frozen revision, dependencies and evidence from one Plan", () => {
    render(<PlanWorkbench planModel={planModel("completed")} evidence={[evidence]} />);
    const workbench = screen.getByRole("main");
    expect(workbench).toHaveAttribute("data-plan-id", "plan_1");
    expect(screen.getByText("实现界面")).toBeVisible();
    expect(screen.getByText(/依赖：/u)).toBeVisible();
    expect(screen.getByText(evidence.summary)).toBeVisible();
  });

  it("keeps execution a separate explicit command after proposal", async () => {
    const execute = vi.fn();
    const user = userEvent.setup();
    render(<PlanWorkbench planModel={planModel("approved")} onExecute={execute} />);
    await user.click(screen.getByRole("button", { name: "执行" }));
    expect(execute).toHaveBeenCalledOnce();
  });

  it("binds pause and stop to the active Plan run", async () => {
    const pause = vi.fn();
    const stop = vi.fn();
    const user = userEvent.setup();
    render(
      <PlanWorkbench planModel={planModel("executing")} onPausePlan={pause} onStopPlan={stop} />,
    );
    await user.click(screen.getByRole("button", { name: "暂停计划" }));
    await user.click(screen.getByRole("button", { name: "停止计划" }));
    expect(pause).toHaveBeenCalledOnce();
    expect(stop).toHaveBeenCalledOnce();
  });

  it("compares immutable revisions by step identity", () => {
    expect(
      revisionDiff({ ...planRevision, steps: planRevision.steps.slice(0, 1) }, planRevision),
    ).toContain("步骤：移除「实现界面」");
  });
});
