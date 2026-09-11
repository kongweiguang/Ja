// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createHash } from "node:crypto";

/**
 * 只在 E2E fixture 内表达稳定错误码；它不冒充 JA-RPC response，也不复制服务端异常层级。
 */
export class PlanGoalFixtureError extends Error {
  /** 保存机器可断言的稳定 code，避免测试解析错误正文。 */
  constructor(code, message) {
    super(message);
    this.name = "PlanGoalFixtureError";
    this.code = code;
  }
}

/**
 * 对 fixture 的 canonical JSON 做稳定摘要；生产 hash 仍必须由 App Server 的 canonical codec 生成。
 */
function fixtureHash(value) {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex");
}

/**
 * 深复制 fixture 投影，防止测试通过修改返回对象绕过 revision 和关联约束。
 */
function snapshot(value) {
  return structuredClone(value);
}

/**
 * 构造可注入、无网络、无磁盘副作用的 Plan/Goal 权威状态替身。它刻意让 Plan 和 Goal
 * 分属两个 Map，只有显式 attach 才建立 link，从测试结构上阻止旧的“建 Goal 即建 Plan”。
 */
export function createPlanGoalStateFixture({ threadId = "thr_plan_goal_fixture" } = {}) {
  const plans = new Map();
  const goals = new Map();
  let planSequence = 0;
  let goalSequence = 0;
  let runSequence = 0;

  /** 以 Thread CAS 独立创建 Plan；创建行为不得隐式产生 Goal。 */
  function createPlan({ objective, expectedThreadRevision = 0 }) {
    if (expectedThreadRevision !== 0) {
      throw new PlanGoalFixtureError("THREAD_REVISION_CONFLICT", "thread revision is stale");
    }
    const planId = `plan_fixture_${++planSequence}`;
    const plan = {
      planId,
      ownerThreadId: threadId,
      objective,
      status: "draft",
      revision: 0,
      draft: null,
      currentRevision: null,
      activeRunId: null,
    };
    plans.set(planId, plan);
    return snapshot(plan);
  }

  /** 保存结构化 Plan draft；保存不冻结 revision，也不创建执行 run。 */
  function savePlanDraft(planId, definition = {}) {
    const plan = requirePlan(planId);
    plan.draft = { objective: plan.objective, ...definition };
    plan.status = "draft";
    return snapshot(plan);
  }

  /** 冻结 draft 为候选 revision；propose 不能隐式批准或执行。 */
  function proposePlan(planId) {
    const plan = requirePlan(planId);
    if (plan.draft === null) {
      throw new PlanGoalFixtureError("PLAN_INVALID", "Plan draft is required");
    }
    const revisionNumber = plan.revision + 1;
    const planRevisionId = `${planId}_revision_${revisionNumber}`;
    const canonical = { ...plan.draft, revisionNumber };
    plan.revision = revisionNumber;
    plan.status = "awaiting_approval";
    plan.currentRevision = {
      planRevisionId,
      planHash: fixtureHash(canonical),
      revisionNumber,
    };
    plan.draft = null;
    return snapshot(plan);
  }

  /** 显式 execute 同时确认当前 revision 并创建 standalone run，防止双调用竞态。 */
  function executePlan(planId, { planRevisionId, planHash } = {}) {
    const plan = requirePlan(planId);
    if (
      (plan.status !== "awaiting_approval" && plan.status !== "approved") ||
      plan.activeRunId !== null
    ) {
      throw new PlanGoalFixtureError("PLAN_INVALID_STATE", "Plan cannot be executed");
    }
    if (
      planRevisionId !== undefined &&
      (plan.currentRevision?.planRevisionId !== planRevisionId ||
        plan.currentRevision?.planHash !== planHash)
    ) {
      throw new PlanGoalFixtureError("PLAN_APPROVAL_STALE", "Plan revision is stale");
    }
    plan.status = "running";
    plan.activeRunId = `plan_run_fixture_${++runSequence}`;
    return snapshot(plan);
  }

  /** 以确定性 fixture 终态完成 standalone Plan run；Goal 状态不参与该迁移。 */
  function completePlan(planId) {
    const plan = requirePlan(planId);
    if (plan.status !== "running" || plan.activeRunId === null) {
      throw new PlanGoalFixtureError("PLAN_INVALID_STATE", "Plan run is not active");
    }
    plan.status = "completed";
    return snapshot(plan);
  }

  /** 创建 Goal-only 运行态；该入口不得隐式创建 Plan 或 link。 */
  function createGoal({ objective, expectedGoalRevision = 0 }) {
    if (expectedGoalRevision !== 0) {
      throw new PlanGoalFixtureError("GOAL_REVISION_CONFLICT", "goal revision is stale");
    }
    const goalId = `goal_fixture_${++goalSequence}`;
    const goal = {
      goalId,
      ownerThreadId: threadId,
      objective,
      status: "active",
      phase: "working",
      revision: 0,
      activeRunId: `goal_only_run_fixture_${++runSequence}`,
      runOwner: "goal",
      planLink: null,
    };
    goals.set(goalId, goal);
    return snapshot(goal);
  }

  /**
   * 只把当前待确认或历史已批准 Plan revision 连接到 Goal；Goal CAS 和 Plan identity 任一过期都必须失败关闭。
   */
  function attachPlan({ goalId, expectedGoalRevision, planId, planRevisionId, planHash }) {
    const goal = requireGoal(goalId);
    requireGoalRevision(goal, expectedGoalRevision);
    const plan = requirePlan(planId);
    if (
      (plan.status !== "awaiting_approval" && plan.status !== "approved") ||
      plan.currentRevision?.planRevisionId !== planRevisionId ||
      plan.currentRevision?.planHash !== planHash
    ) {
      throw new PlanGoalFixtureError(
        "PLAN_APPROVAL_STALE",
        "attached Plan revision is not the current revision",
      );
    }
    goal.revision += 1;
    goal.activeRunId = `goal_plan_run_fixture_${++runSequence}`;
    goal.planLink = {
      planId,
      planRevisionId,
      planHash,
      linkRevision: goal.revision,
    };
    return snapshot(goal);
  }

  /**
   * 解除关联只推进 Goal revision；Goal 保持 ACTIVE/WORKING，独立 Plan run identity 不得被停止或改写。
   */
  function detachPlan({ goalId, expectedGoalRevision }) {
    const goal = requireGoal(goalId);
    requireGoalRevision(goal, expectedGoalRevision);
    goal.revision += 1;
    goal.activeRunId = `goal_only_run_fixture_${++runSequence}`;
    goal.planLink = null;
    return snapshot(goal);
  }

  /** 读取独立 Plan 投影；不存在时使用与生产合同一致的 not-found 分类。 */
  function readPlan(planId) {
    return snapshot(requirePlan(planId));
  }

  /** 读取独立 Goal 投影；返回副本以保持 fixture 的单一写入边界。 */
  function readGoal(goalId) {
    return snapshot(requireGoal(goalId));
  }

  /** 返回数量关系，专门证明任一聚合的创建都不会隐式创建另一个聚合。 */
  function inventory() {
    return { plans: plans.size, goals: goals.size };
  }

  /** 在唯一入口解析 Plan identity，避免每个操作产生不同的缺失语义。 */
  function requirePlan(planId) {
    const plan = plans.get(planId);
    if (plan === undefined) throw new PlanGoalFixtureError("PLAN_NOT_FOUND", "Plan not found");
    return plan;
  }

  /** 在唯一入口解析 Goal identity，避免测试误把 undefined 当作空 Goal。 */
  function requireGoal(goalId) {
    const goal = goals.get(goalId);
    if (goal === undefined) throw new PlanGoalFixtureError("GOAL_NOT_FOUND", "Goal not found");
    return goal;
  }

  /** Goal mutation 只接受精确 revision；full access 也不改变这一业务 CAS。 */
  function requireGoalRevision(goal, expectedGoalRevision) {
    if (goal.revision !== expectedGoalRevision) {
      throw new PlanGoalFixtureError("GOAL_REVISION_CONFLICT", "goal revision is stale");
    }
  }

  return Object.freeze({
    createPlan,
    savePlanDraft,
    proposePlan,
    executePlan,
    completePlan,
    createGoal,
    attachPlan,
    detachPlan,
    readPlan,
    readGoal,
    inventory,
  });
}
