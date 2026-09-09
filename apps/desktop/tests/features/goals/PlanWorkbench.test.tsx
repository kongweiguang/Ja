// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  PlanWorkbench,
  editableDraft,
  revisionDiff,
  type PlanReadModel,
  type PlanStatus,
} from "@/features/goals";
import { evidence, goalModel, goalOnlyEvidence, planRevision } from "./goalFixtures";

/** 由同一冻结 revision 构造 standalone Plan 投影，避免测试通过伪 Goal 承载计划状态。 */
function standalonePlan(status: PlanStatus): PlanReadModel {
  return {
    plan: {
      planId: "plan_1",
      ownerThreadId: "thr_1",
      objective: planRevision.objective,
      status,
      revision: 3,
      activePlanRevisionId: status === "approved" ? planRevision.planRevisionId : null,
      activeRunId: null,
      createdAt: planRevision.createdAt,
      updatedAt: planRevision.createdAt,
    },
    revision: {
      ...planRevision,
      approvedAt: status === "approved" ? "2026-09-04T10:05:00+08:00" : null,
    },
    draft: null,
    approvedPlanRevisionId: status === "approved" ? planRevision.planRevisionId : null,
    eventSequence: 3,
  };
}

describe("PlanWorkbench", () => {
  afterEach(cleanup);

  /** 未关联 Plan 的 Goal 直接展示 definition 与 nullable-bound 证据，不落入“没有计划”空状态。 */
  it("renders Goal-only evidence without inventing a Plan identity", () => {
    const current = goalModel();
    const goalOnly = {
      ...current,
      goal: {
        ...current.goal,
        activePlanId: null,
        activePlanRevisionId: null,
        activePlanHash: null,
        currentStepId: null,
        completedRequiredSteps: 0,
        totalRequiredSteps: 0,
      },
      planState: null,
      plan: null,
      draft: null,
    };
    render(<PlanWorkbench model={goalOnly} evidence={[goalOnlyEvidence]} />);

    const workbench = screen.getByRole("main");
    expect(workbench).toHaveAttribute("data-goal-id", "goal_1");
    expect(workbench).not.toHaveAttribute("data-plan-id");
    expect(screen.getByText("Goal-only recovery verified")).toBeVisible();
    expect(screen.queryByText("当前会话没有计划")).not.toBeInTheDocument();
  });

  /** 内部 revision 仍保持不可变，但空状态不能把实现术语暴露给用户。 */
  it("describes an absent revision without freeze terminology", () => {
    const model = goalModel("working", "draft");
    render(
      <PlanWorkbench
        model={{
          ...model,
          goal: { ...model.goal, activePlanRevisionId: null },
          plan: null,
        }}
      />,
    );

    expect(screen.getByText("尚无已确认版本")).toBeVisible();
    expect(screen.queryByText("尚无冻结版本")).not.toBeInTheDocument();
  });

  it("renders dependencies, evidence and approval without starting execution", async () => {
    const user = userEvent.setup();
    const approve = vi.fn();
    const execute = vi.fn();
    render(
      <PlanWorkbench
        model={goalModel()}
        evidence={[evidence]}
        onApprove={approve}
        onExecute={execute}
        onReject={vi.fn()}
        onSaveDraft={vi.fn()}
      />,
    );

    expect(screen.getByText("依赖：冻结契约")).toBeVisible();
    expect(screen.getByText("Goal UI tests passed")).toBeVisible();
    expect(screen.getByRole("button", { name: "批准" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "执行计划" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "继续处理" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "批准" }));
    expect(approve).toHaveBeenCalledOnce();
    expect(execute).not.toHaveBeenCalled();
  });

  /** standalone Plan 的批准后主动作仅启动单次 Plan run，不隐式创建或关联 Goal。 */
  it("executes an approved standalone Plan explicitly", async () => {
    const user = userEvent.setup();
    const execute = vi.fn();
    render(<PlanWorkbench planModel={standalonePlan("approved")} onExecute={execute} />);

    expect(screen.queryByRole("button", { name: "批准" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "执行计划" }));
    expect(execute).toHaveBeenCalledOnce();
  });

  /** 同时存在 Goal 时仍需用户显式选择 attach，不能把 standalone execute 当作 Goal run。 */
  it("offers an approved Plan to the current Goal as a distinct action", async () => {
    const user = userEvent.setup();
    const attach = vi.fn();
    const execute = vi.fn();
    const current = goalModel("working", "approved");
    render(
      <PlanWorkbench
        model={{
          ...current,
          goal: {
            ...current.goal,
            activePlanId: null,
            activePlanRevisionId: null,
            activePlanHash: null,
          },
        }}
        planModel={standalonePlan("approved")}
        onAttachPlan={attach}
        onExecute={execute}
      />,
    );

    expect(screen.queryByRole("button", { name: "执行计划" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "用于当前目标" }));
    expect(attach).toHaveBeenCalledOnce();
    expect(execute).not.toHaveBeenCalled();
  });

  it("replaces pause with the only continuation action after not-met evaluation", async () => {
    const user = userEvent.setup();
    const continueGoal = vi.fn();
    const working = goalModel("working");
    const notMet = goalModel("needs_attention").evaluation;
    render(
      <PlanWorkbench
        model={{ ...working, evaluation: notMet }}
        onPause={vi.fn()}
        onContinue={continueGoal}
      />,
    );

    expect(screen.queryByRole("button", { name: "暂停目标" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "继续处理" }));
    expect(continueGoal).toHaveBeenCalledOnce();
  });

  it("edits an awaiting-approval revision without exposing stale approval actions", async () => {
    const user = userEvent.setup();
    render(
      <PlanWorkbench
        model={goalModel("working", "awaiting_approval")}
        onSaveDraft={vi.fn()}
        onApprove={vi.fn()}
        onReject={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "编辑计划" }));

    expect(screen.getByRole("region", { name: "编辑计划草稿" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "批准" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "拒绝此版本" })).not.toBeInTheDocument();
  });

  it("edits a complete structured draft while preserving step identities", async () => {
    const save = vi.fn();
    const user = userEvent.setup();
    render(<PlanWorkbench model={goalModel("working", "draft")} onSaveDraft={save} />);

    await user.click(screen.getByRole("button", { name: "编辑计划" }));
    const objective = screen.getByRole("textbox", { name: "目标" });
    const scope = screen.getByRole("textbox", { name: "范围" });
    const title = screen.getByRole("textbox", { name: "步骤 2 标题" });
    expect(objective).toHaveAccessibleName("目标");
    expect(scope).toHaveAccessibleName("范围");
    await user.clear(title);
    await user.type(title, "完成生产界面");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));

    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({
        steps: expect.arrayContaining([
          expect.objectContaining({ stepId: "step_ui", title: "完成生产界面" }),
        ]),
      }),
    );
  });

  it("submits user input only when non-empty", async () => {
    const respond = vi.fn();
    const user = userEvent.setup();
    render(<PlanWorkbench model={goalModel("waiting_input")} onRespondInput={respond} />);
    const submit = screen.getByRole("button", { name: "提交输入" });
    expect(submit).toBeDisabled();
    await user.type(screen.getByRole("textbox", { name: "目标所需输入" }), "保留当前工作区");
    await user.click(submit);
    expect(respond).toHaveBeenCalledWith("保留当前工作区");
  });

  it("compares structured revisions instead of markdown text", () => {
    const previous = { ...planRevision, planRevisionId: "planrev_1", revisionNumber: 1, risks: [] };
    expect(revisionDiff(planRevision, previous)).toEqual(["风险"]);
    expect(editableDraft(goalModel()).steps.map((step) => step.stepId)).toEqual([
      "step_contract",
      "step_ui",
    ]);
  });

  it("waits for pause acknowledgement before editing and resumes when local edit is cancelled", async () => {
    const beginEdit = vi.fn(async () => true);
    const cancelEdit = vi.fn(async () => true);
    const user = userEvent.setup();
    render(
      <PlanWorkbench
        model={goalModel("working")}
        onSaveDraft={vi.fn()}
        onBeginEdit={beginEdit}
        onCancelEdit={cancelEdit}
      />,
    );

    await user.click(screen.getByRole("button", { name: "编辑计划" }));
    expect(beginEdit).toHaveBeenCalledOnce();
    expect(screen.getByRole("region", { name: "编辑计划草稿" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "取消" }));
    expect(cancelEdit).toHaveBeenCalledOnce();
  });

  it("keeps an approved plan read-only when pause is not acknowledged", async () => {
    const user = userEvent.setup();
    render(
      <PlanWorkbench
        model={goalModel("working")}
        onSaveDraft={vi.fn()}
        onBeginEdit={vi.fn(async () => false)}
      />,
    );

    await user.click(screen.getByRole("button", { name: "编辑计划" }));
    expect(screen.queryByRole("region", { name: "编辑计划草稿" })).not.toBeInTheDocument();
  });

  it("preserves an unsaved local edit across a late same-goal refresh", async () => {
    const user = userEvent.setup();
    const initial = goalModel("working", "draft");
    const { rerender } = render(<PlanWorkbench model={initial} onSaveDraft={vi.fn()} />);

    await user.click(screen.getByRole("button", { name: "编辑计划" }));
    const objective = screen.getByRole("textbox", { name: "目标" });
    await user.clear(objective);
    await user.type(objective, "尚未保存的本地目标");

    rerender(
      <PlanWorkbench
        model={{
          ...initial,
          goal: { ...initial.goal, revision: initial.goal.revision + 1 },
        }}
        onSaveDraft={vi.fn()}
      />,
    );

    expect(screen.getByRole("textbox", { name: "目标" })).toHaveValue("尚未保存的本地目标");
  });
});
