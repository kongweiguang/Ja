// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import {
  parseMethodParams,
  parseMethodResult,
  type MethodParams,
  type MethodResult,
} from "../protocol/methods";
import type { GoalMethod } from "../protocol/goal";
import {
  defaultNativeBridge,
  normalizeRuntimeError,
  RuntimeHostError,
  type RuntimeNativeBridge,
} from "./runtime";

export const JA_GOAL_COMMANDS = {
  read: "ja_runtime_goal_read",
  eventsRead: "ja_runtime_goal_events_read",
  observe: "ja_runtime_goal_observe",
  unobserve: "ja_runtime_goal_unobserve",
  planRead: "ja_runtime_plan_read",
  revisionsList: "ja_runtime_plan_revisions_list",
  evidenceList: "ja_runtime_goal_evidence_list",
  goalCreate: "ja_runtime_goal_create",
  goalPlanAttach: "ja_runtime_goal_plan_attach",
  goalPlanDetach: "ja_runtime_goal_plan_detach",
  pause: "ja_runtime_goal_pause",
  resume: "ja_runtime_goal_resume",
  stop: "ja_runtime_goal_stop",
  inputRespond: "ja_runtime_goal_input_respond",
  planCreate: "ja_runtime_plan_create",
  draftSave: "ja_runtime_plan_draft_save",
  draftDiscard: "ja_runtime_plan_draft_discard",
  propose: "ja_runtime_plan_propose",
  approve: "ja_runtime_plan_approve",
  execute: "ja_runtime_plan_execute",
  reject: "ja_runtime_plan_reject",
} as const;

export type GoalReadInput = MethodParams<"goal/read">;
export type GoalReadResult = MethodResult<"goal/read">;
export type GoalEventsReadInput = MethodParams<"goal/events/read">;
export type GoalEventsReadResult = MethodResult<"goal/events/read">;
export type GoalObserveInput = MethodParams<"goal/observe">;
export type GoalObserveResult = MethodResult<"goal/observe">;
export type GoalUnobserveInput = MethodParams<"goal/unobserve">;
export type PlanReadInput = MethodParams<"plan/read">;
export type PlanReadResult = MethodResult<"plan/read">;
export type PlanRevisionsListInput = MethodParams<"plan/revisions/list">;
export type PlanRevisionsListResult = MethodResult<"plan/revisions/list">;
export type GoalEvidenceListInput = MethodParams<"goal/evidence/list">;
export type GoalEvidenceListResult = MethodResult<"goal/evidence/list">;
export type GoalCreateInput = MethodParams<"goal/create">;
export type GoalMutationInput = MethodParams<"goal/pause">;
export type GoalMutationResult = MethodResult<"goal/pause">;
export type GoalPlanAttachInput = MethodParams<"goal/plan/attach">;
export type GoalInputRespondInput = MethodParams<"goal/input/respond">;
export type PlanCreateInput = MethodParams<"plan/create">;
export type PlanMutationInput = MethodParams<"plan/draft/discard">;
export type PlanMutationResult = MethodResult<"plan/draft/discard">;
export type PlanDraftSaveInput = MethodParams<"plan/draft/save">;
export type PlanApproveInput = MethodParams<"plan/approve">;
export type PlanExecuteInput = MethodParams<"plan/execute">;
export type PlanRejectInput = MethodParams<"plan/reject">;

export interface GoalAdapter {
  read(input: GoalReadInput): Promise<GoalReadResult>;
  eventsRead(input: GoalEventsReadInput): Promise<GoalEventsReadResult>;
  observe(input: GoalObserveInput): Promise<GoalObserveResult>;
  unobserve(input: GoalUnobserveInput): Promise<void>;
  planRead(input: PlanReadInput): Promise<PlanReadResult>;
  revisionsList(input: PlanRevisionsListInput): Promise<PlanRevisionsListResult>;
  evidenceList(input: GoalEvidenceListInput): Promise<GoalEvidenceListResult>;
  create(input: GoalCreateInput): Promise<GoalMutationResult>;
  attachPlan(input: GoalPlanAttachInput): Promise<GoalMutationResult>;
  detachPlan(input: GoalMutationInput): Promise<GoalMutationResult>;
  pause(input: GoalMutationInput): Promise<GoalMutationResult>;
  resume(input: GoalMutationInput): Promise<GoalMutationResult>;
  stop(input: GoalMutationInput): Promise<GoalMutationResult>;
  inputRespond(input: GoalInputRespondInput): Promise<GoalMutationResult>;
  createPlan(input: PlanCreateInput): Promise<PlanMutationResult>;
  draftSave(input: PlanDraftSaveInput): Promise<PlanMutationResult>;
  draftDiscard(input: PlanMutationInput): Promise<PlanMutationResult>;
  propose(input: PlanMutationInput): Promise<PlanMutationResult>;
  approve(input: PlanApproveInput): Promise<PlanMutationResult>;
  execute(input: PlanExecuteInput): Promise<PlanMutationResult>;
  reject(input: PlanRejectInput): Promise<PlanMutationResult>;
}

/** 固定 method/command 对经过同一严格目录校验，Renderer 无法构造通用 JA-RPC 调用。 */
async function invokeGoal<M extends GoalMethod>(
  bridge: RuntimeNativeBridge,
  command: (typeof JA_GOAL_COMMANDS)[keyof typeof JA_GOAL_COMMANDS],
  method: M,
  input: MethodParams<M>,
): Promise<MethodResult<M>> {
  let parsed: MethodParams<M>;
  try {
    parsed = parseMethodParams(method, input);
  } catch {
    throw new RuntimeHostError("INVALID_INPUT", "请求参数无效", false);
  }
  try {
    const result = await bridge.invoke<unknown>(command, { input: parsed });
    return parseMethodResult(method, result);
  } catch (error) {
    if (error instanceof z.ZodError) {
      throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时暂不可用", true);
    }
    throw normalizeRuntimeError(error);
  }
}

/** Goal adapter 只暴露产品动作，批准与 AccessMode 保持正交且必须显式调用 approve。 */
export class TauriGoalAdapter implements GoalAdapter {
  /** bridge 仅供合同测试注入，生产复用 Runtime 的唯一 Tauri IPC 边界。 */
  constructor(private readonly bridge: RuntimeNativeBridge = defaultNativeBridge) {}

  /** 返回 Goal 与当前 Plan 的同序列快照，reload 不从事件增量重建。 */
  read(input: GoalReadInput): Promise<GoalReadResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.read, "goal/read", input);
  }

  /** 历史事件使用有界 cursor，不能借查询参数触发 continuation。 */
  eventsRead(input: GoalEventsReadInput): Promise<GoalEventsReadResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.eventsRead, "goal/events/read", input);
  }

  /** observation 只路由事件，权威快照仍随 ACK 一并返回。 */
  observe(input: GoalObserveInput): Promise<GoalObserveResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.observe, "goal/observe", input);
  }

  /** 释放观察句柄不暂停 Goal，也不取消正在运行的 Turn。 */
  async unobserve(input: GoalUnobserveInput): Promise<void> {
    await invokeGoal(this.bridge, JA_GOAL_COMMANDS.unobserve, "goal/unobserve", input);
  }

  /** 读取当前 draft/revision/approval/step/evaluation 完整投影。 */
  planRead(input: PlanReadInput): Promise<PlanReadResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.planRead, "plan/read", input);
  }

  /** revision 历史只按服务端 cursor 分页，不在客户端生成 diff 身份。 */
  revisionsList(input: PlanRevisionsListInput): Promise<PlanRevisionsListResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.revisionsList, "plan/revisions/list", input);
  }

  /** 证据查询必须绑定冻结计划版本，避免展示旧 revision 的已失效验收。 */
  evidenceList(input: GoalEvidenceListInput): Promise<GoalEvidenceListResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.evidenceList, "goal/evidence/list", input);
  }

  /** create 使用 expectedGoalRevision=0 和幂等键，不允许本地预建第二状态源。 */
  create(input: GoalCreateInput): Promise<GoalMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.goalCreate, "goal/create", input);
  }

  /** 绑定只消费已批准蓝图并创建 Goal-owned run，不复用 standalone Plan run。 */
  attachPlan(input: GoalPlanAttachInput): Promise<GoalMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.goalPlanAttach, "goal/plan/attach", input);
  }

  /** 解除 link 后 Goal 切回 Goal-only run，Plan 历史保持不变。 */
  detachPlan(input: GoalMutationInput): Promise<GoalMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.goalPlanDetach, "goal/plan/detach", input);
  }

  /** 暂停先由 Java 持久化，再取消当前 continuation Turn。 */
  pause(input: GoalMutationInput): Promise<GoalMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.pause, "goal/pause", input);
  }

  /** 恢复仅提交显式用户意图，启动时机继续由 Goal coordinator 决定。 */
  resume(input: GoalMutationInput): Promise<GoalMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.resume, "goal/resume", input);
  }

  /** 停止是不可恢复终态，因此保持独立 command 和幂等键。 */
  stop(input: GoalMutationInput): Promise<GoalMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.stop, "goal/stop", input);
  }

  /** 回复绑定服务端 input request identity，过期请求不得静默写入下一轮。 */
  inputRespond(input: GoalInputRespondInput): Promise<GoalMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.inputRespond, "goal/input/respond", input);
  }

  /** 创建独立 Plan 只绑定 Thread，不隐式创建或激活 Goal。 */
  createPlan(input: PlanCreateInput): Promise<PlanMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.planCreate, "plan/create", input);
  }

  /** draft 使用结构化定义而非 Markdown，CAS 失败时保留调用方编辑内容。 */
  draftSave(input: PlanDraftSaveInput): Promise<PlanMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.draftSave, "plan/draft/save", input);
  }

  /** discard 只丢弃未冻结 draft，不改变已批准 revision。 */
  draftDiscard(input: PlanMutationInput): Promise<PlanMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.draftDiscard, "plan/draft/discard", input);
  }

  /** propose 在服务端冻结 canonical JSON/hash，客户端不自行计算权威 digest。 */
  propose(input: PlanMutationInput): Promise<PlanMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.propose, "plan/propose", input);
  }

  /** 批准绑定精确 revision 与 hash，即使 full_access 也不能省略。 */
  approve(input: PlanApproveInput): Promise<PlanMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.approve, "plan/approve", input);
  }

  /** execute 显式启动一次隐藏 PLAN_EXECUTION Turn，不进入 Goal 自动续跑。 */
  execute(input: PlanExecuteInput): Promise<PlanMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.execute, "plan/execute", input);
  }

  /** reject 以 Goal CAS 标记当前提案并回到可编辑 draft，不隐式批准旧版本。 */
  reject(input: PlanRejectInput): Promise<PlanMutationResult> {
    return invokeGoal(this.bridge, JA_GOAL_COMMANDS.reject, "plan/reject", input);
  }
}

/** 生产工厂返回窄接口，feature 不持有 Tauri command 名或 raw invoke。 */
export function createGoalAdapter(): GoalAdapter {
  return new TauriGoalAdapter();
}
