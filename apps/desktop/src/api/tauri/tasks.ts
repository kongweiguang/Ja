// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import {
  parseMethodParams,
  parseMethodResult,
  type MethodParams,
  type MethodResult,
} from "../protocol/methods";
import {
  defaultNativeBridge,
  normalizeRuntimeError,
  RuntimeHostError,
  type RuntimeNativeBridge,
} from "./runtime";

export const JA_TASK_COMMANDS = {
  create: "ja_runtime_task_create",
  list: "ja_runtime_task_list",
  read: "ja_runtime_task_read",
  observe: "ja_runtime_task_observe",
  unobserve: "ja_runtime_task_unobserve",
  seen: "ja_runtime_task_seen",
  messageSend: "ja_runtime_task_message_send",
  followup: "ja_runtime_task_followup",
  cancel: "ja_runtime_task_cancel",
  treeDelete: "ja_runtime_task_tree_delete",
} as const;

export type TaskCreateInput = MethodParams<"task/create">;
export type TaskCreateResult = MethodResult<"task/create">;
export type TaskListInput = MethodParams<"task/list">;
export type TaskListResult = MethodResult<"task/list">;
export type TaskReadInput = MethodParams<"task/read">;
export type TaskReadResult = MethodResult<"task/read">;
export type TaskObserveInput = MethodParams<"task/observe">;
export type TaskObserveResult = MethodResult<"task/observe">;
export type TaskUnobserveInput = MethodParams<"task/unobserve">;
export type TaskSeenInput = MethodParams<"task/seen">;
export type TaskMessageInput = MethodParams<"task/message/send">;
export type TaskMessageResult = MethodResult<"task/message/send">;
export type TaskFollowupInput = MethodParams<"task/followup">;
export type TaskFollowupResult = MethodResult<"task/followup">;
export type TaskMutationInput = MethodParams<"task/cancel">;
export type TaskMutationResult = MethodResult<"task/cancel">;
export type TaskTreeDeleteInput = MethodParams<"task/tree/delete">;
export type TaskTreeDeleteResult = MethodResult<"task/tree/delete">;

type TaskMethod =
  | "task/create"
  | "task/list"
  | "task/read"
  | "task/observe"
  | "task/unobserve"
  | "task/seen"
  | "task/message/send"
  | "task/followup"
  | "task/cancel"
  | "task/tree/delete";

export interface TaskAdapter {
  create(input: TaskCreateInput): Promise<TaskCreateResult>;
  list(input: TaskListInput): Promise<TaskListResult>;
  read(input: TaskReadInput): Promise<TaskReadResult>;
  observe(input: TaskObserveInput): Promise<TaskObserveResult>;
  unobserve(input: TaskUnobserveInput): Promise<void>;
  seen(input: TaskSeenInput): Promise<TaskMutationResult>;
  messageSend(input: TaskMessageInput): Promise<TaskMessageResult>;
  followup(input: TaskFollowupInput): Promise<TaskFollowupResult>;
  cancel(input: TaskMutationInput): Promise<TaskMutationResult>;
  treeDelete(input: TaskTreeDeleteInput): Promise<TaskTreeDeleteResult>;
}

/**
 * 每个产品动作绑定一个专用 Tauri command；method 只在 adapter 内部选择，Renderer 调用方
 * 无法把这个边界降级为通用 JA-RPC tunnel。
 */
async function invokeTask<M extends TaskMethod>(
  bridge: RuntimeNativeBridge,
  command: (typeof JA_TASK_COMMANDS)[keyof typeof JA_TASK_COMMANDS],
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

/** 专用 Task adapter 保持 observe/unobserve 对称，并把所有结果留在 canonical Schema 门内。 */
export class TauriTaskAdapter implements TaskAdapter {
  /** bridge 仅供合同测试注入；生产始终使用 Runtime 的单一原生边界。 */
  constructor(private readonly bridge: RuntimeNativeBridge = defaultNativeBridge) {}

  /** 侧边任务只通过用户专用 create command 创建，不能从 UI 冒充 Subagent。 */
  create(input: TaskCreateInput): Promise<TaskCreateResult> {
    return invokeTask(this.bridge, JA_TASK_COMMANDS.create, "task/create", input);
  }

  /** 总览只返回根任务的有界摘要，不物化任何 Child Transcript。 */
  list(input: TaskListInput): Promise<TaskListResult> {
    return invokeTask(this.bridge, JA_TASK_COMMANDS.list, "task/list", input);
  }

  /** 详情元数据与 Mailbox 使用有界分页读取，正文继续由 thread/read 独立负责。 */
  read(input: TaskReadInput): Promise<TaskReadResult> {
    return invokeTask(this.bridge, JA_TASK_COMMANDS.read, "task/read", input);
  }

  /** 高频观察必须携带 revision CAS，防止详情切换后订阅陈旧实例。 */
  observe(input: TaskObserveInput): Promise<TaskObserveResult> {
    return invokeTask(this.bridge, JA_TASK_COMMANDS.observe, "task/observe", input);
  }

  /** 关闭详情只释放观察句柄，不取消正在执行的任务。 */
  async unobserve(input: TaskUnobserveInput): Promise<void> {
    await invokeTask(this.bridge, JA_TASK_COMMANDS.unobserve, "task/unobserve", input);
  }

  /** 已读边界由服务端 sequence 与 revision CAS 决定，UI 不本地猜测消费进度。 */
  seen(input: TaskSeenInput): Promise<TaskMutationResult> {
    return invokeTask(this.bridge, JA_TASK_COMMANDS.seen, "task/seen", input);
  }

  /** QueueOnly 消息不会在目标空闲时隐式启动新 Turn。 */
  messageSend(input: TaskMessageInput): Promise<TaskMessageResult> {
    return invokeTask(this.bridge, JA_TASK_COMMANDS.messageSend, "task/message/send", input);
  }

  /** follow-up 是唯一允许从详情显式启动或排队 Child Turn 的交互。 */
  followup(input: TaskFollowupInput): Promise<TaskFollowupResult> {
    return invokeTask(this.bridge, JA_TASK_COMMANDS.followup, "task/followup", input);
  }

  /** 取消提交传播意图；最终状态仍以持久事件和重读投影为准。 */
  cancel(input: TaskMutationInput): Promise<TaskMutationResult> {
    return invokeTask(this.bridge, JA_TASK_COMMANDS.cancel, "task/cancel", input);
  }

  /** 整树删除要求双重 identity，避免普通关闭 Tab 或删除 Thread 产生数据破坏。 */
  treeDelete(input: TaskTreeDeleteInput): Promise<TaskTreeDeleteResult> {
    return invokeTask(this.bridge, JA_TASK_COMMANDS.treeDelete, "task/tree/delete", input);
  }
}

/** 生产工厂只返回窄 Task surface，调用方接触不到 bridge 或 command 名。 */
export function createTaskAdapter(): TaskAdapter {
  return new TauriTaskAdapter();
}
