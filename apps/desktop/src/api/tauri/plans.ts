// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import {
  parseMethodParams,
  parseMethodResult,
  type MethodParams,
  type MethodResult,
} from "../protocol/methods";
import type { PlanMethod } from "../protocol/plan";
import {
  defaultNativeBridge,
  normalizeRuntimeError,
  RuntimeHostError,
  type RuntimeNativeBridge,
} from "./runtime";

export const JA_PLAN_COMMANDS = {
  read: "ja_runtime_plan_read",
  currentRead: "ja_runtime_plan_current_read",
  revisionsList: "ja_runtime_plan_revisions_list",
  create: "ja_runtime_plan_create",
  draftSave: "ja_runtime_plan_draft_save",
  draftDiscard: "ja_runtime_plan_draft_discard",
  propose: "ja_runtime_plan_propose",
  execute: "ja_runtime_plan_execute",
  observe: "ja_runtime_plan_observe",
  unobserve: "ja_runtime_plan_unobserve",
  eventsRead: "ja_runtime_plan_events_read",
  evidenceList: "ja_runtime_plan_evidence_list",
  pause: "ja_runtime_plan_pause",
  resume: "ja_runtime_plan_resume",
  stop: "ja_runtime_plan_stop",
  reject: "ja_runtime_plan_reject",
} as const;

export interface PlanAdapter {
  read(input: MethodParams<"plan/read">): Promise<MethodResult<"plan/read">>;
  currentRead(input: MethodParams<"plan/current/read">): Promise<MethodResult<"plan/current/read">>;
  revisionsList(
    input: MethodParams<"plan/revisions/list">,
  ): Promise<MethodResult<"plan/revisions/list">>;
  create(input: MethodParams<"plan/create">): Promise<MethodResult<"plan/create">>;
  draftSave(input: MethodParams<"plan/draft/save">): Promise<MethodResult<"plan/draft/save">>;
  draftDiscard(
    input: MethodParams<"plan/draft/discard">,
  ): Promise<MethodResult<"plan/draft/discard">>;
  propose(input: MethodParams<"plan/propose">): Promise<MethodResult<"plan/propose">>;
  execute(input: MethodParams<"plan/execute">): Promise<MethodResult<"plan/execute">>;
  observe(input: MethodParams<"plan/observe">): Promise<MethodResult<"plan/observe">>;
  unobserve(input: MethodParams<"plan/unobserve">): Promise<MethodResult<"plan/unobserve">>;
  eventsRead(input: MethodParams<"plan/events/read">): Promise<MethodResult<"plan/events/read">>;
  evidenceList(
    input: MethodParams<"plan/evidence/list">,
  ): Promise<MethodResult<"plan/evidence/list">>;
  pause(input: MethodParams<"plan/pause">): Promise<MethodResult<"plan/pause">>;
  resume(input: MethodParams<"plan/resume">): Promise<MethodResult<"plan/resume">>;
  stop(input: MethodParams<"plan/stop">): Promise<MethodResult<"plan/stop">>;
  reject(input: MethodParams<"plan/reject">): Promise<MethodResult<"plan/reject">>;
}

/** 固定的 method/command 对先经过严格目录校验，Renderer 无法构造通用 JA-RPC 调用。 */
async function invokePlan<M extends PlanMethod>(
  bridge: RuntimeNativeBridge,
  command: (typeof JA_PLAN_COMMANDS)[keyof typeof JA_PLAN_COMMANDS],
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

/** Plan 适配器只公开类型化动作，所有状态以 Java 返回的完整投影为准。 */
export class TauriPlanAdapter implements PlanAdapter {
  /** bridge 仅供合同测试注入，生产复用唯一 Tauri IPC 边界。 */
  constructor(private readonly bridge: RuntimeNativeBridge = defaultNativeBridge) {}

  /** 查询与修改共用同一服务端投影，避免客户端拼接 CAS 状态。 */
  read(input: MethodParams<"plan/read">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.read, "plan/read", input);
  }
  /** Thread 恢复时允许不存在 Plan。 */
  currentRead(input: MethodParams<"plan/current/read">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.currentRead, "plan/current/read", input);
  }
  /** 版本按服务端 cursor 分页，不由 UI 生成版本身份。 */
  revisionsList(input: MethodParams<"plan/revisions/list">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.revisionsList, "plan/revisions/list", input);
  }
  /** 创建绑定 Thread CAS 与幂等身份。 */
  create(input: MethodParams<"plan/create">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.create, "plan/create", input);
  }
  /** 草稿保持结构化定义与完整版本。 */
  draftSave(input: MethodParams<"plan/draft/save">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.draftSave, "plan/draft/save", input);
  }
  /** 丢弃草稿不影响已冻结版本。 */
  draftDiscard(input: MethodParams<"plan/draft/discard">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.draftDiscard, "plan/draft/discard", input);
  }
  /** 定稿 hash 只由服务端计算。 */
  propose(input: MethodParams<"plan/propose">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.propose, "plan/propose", input);
  }
  /** 执行授权绑定冻结版本与幂等身份。 */
  execute(input: MethodParams<"plan/execute">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.execute, "plan/execute", input);
  }
  /** 观察 ACK 同时返回当前权威状态。 */
  observe(input: MethodParams<"plan/observe">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.observe, "plan/observe", input);
  }
  /** 释放连接级观察资源，不改变执行状态。 */
  unobserve(input: MethodParams<"plan/unobserve">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.unobserve, "plan/unobserve", input);
  }
  /** 历史事件按需分页加载。 */
  eventsRead(input: MethodParams<"plan/events/read">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.eventsRead, "plan/events/read", input);
  }
  /** 证据严格绑定 Plan revision 和 Run。 */
  evidenceList(input: MethodParams<"plan/evidence/list">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.evidenceList, "plan/evidence/list", input);
  }
  /** 控制动作保留原 Run 身份。 */
  pause(input: MethodParams<"plan/pause">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.pause, "plan/pause", input);
  }
  /** 恢复动作保留原 Run 身份。 */
  resume(input: MethodParams<"plan/resume">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.resume, "plan/resume", input);
  }
  /** 停止动作保留历史和已产生副作用。 */
  stop(input: MethodParams<"plan/stop">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.stop, "plan/stop", input);
  }
  /** 拒绝当前版本后返回可编辑状态。 */
  reject(input: MethodParams<"plan/reject">) {
    return invokePlan(this.bridge, JA_PLAN_COMMANDS.reject, "plan/reject", input);
  }
}

/** 工厂收窄 Native 边界，业务组件不接触原始命令。 */
export function createPlanAdapter(): PlanAdapter {
  return new TauriPlanAdapter();
}
