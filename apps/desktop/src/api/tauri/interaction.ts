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

export const JA_INTERACTION_COMMANDS = {
  read: "ja_runtime_interaction_read",
  observe: "ja_runtime_interaction_observe",
  unobserve: "ja_runtime_interaction_unobserve",
  draftSave: "ja_runtime_interaction_draft_save",
  respond: "ja_runtime_interaction_respond",
  cancel: "ja_runtime_interaction_cancel",
} as const;

export type InteractionMethod = keyof typeof JA_INTERACTION_COMMANDS extends never
  ? never
  :
      | "interaction/read"
      | "interaction/observe"
      | "interaction/unobserve"
      | "interaction/draft/save"
      | "interaction/respond"
      | "interaction/cancel";

export interface InteractionAdapter {
  read(input: MethodParams<"interaction/read">): Promise<MethodResult<"interaction/read">>;
  observe(input: MethodParams<"interaction/observe">): Promise<MethodResult<"interaction/observe">>;
  unobserve(
    input: MethodParams<"interaction/unobserve">,
  ): Promise<MethodResult<"interaction/unobserve">>;
  draftSave(
    input: MethodParams<"interaction/draft/save">,
  ): Promise<MethodResult<"interaction/draft/save">>;
  respond(input: MethodParams<"interaction/respond">): Promise<MethodResult<"interaction/respond">>;
  cancel(input: MethodParams<"interaction/cancel">): Promise<MethodResult<"interaction/cancel">>;
}

/**
 * Interaction command 的参数和返回值必须在 native bridge 边界双向校验；
 * 这样重连或旧 sidecar 的错误投影不会污染 React 的等待状态，也不会把 raw invoke 暴露给 UI。
 */
async function invokeInteraction<M extends InteractionMethod>(
  bridge: RuntimeNativeBridge,
  command: (typeof JA_INTERACTION_COMMANDS)[keyof typeof JA_INTERACTION_COMMANDS],
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

/** Interaction adapter 仅发布产品动作，事件订阅仍由 RuntimeHost 的统一事件入口负责。 */
export class TauriInteractionAdapter implements InteractionAdapter {
  constructor(private readonly bridge: RuntimeNativeBridge = defaultNativeBridge) {}

  /** 读取当前 Thread 的权威 Interaction 快照，断线时由上层 controller 负责恢复。 */
  read(input: MethodParams<"interaction/read">): Promise<MethodResult<"interaction/read">> {
    return invokeInteraction(this.bridge, JA_INTERACTION_COMMANDS.read, "interaction/read", input);
  }

  /** 订阅与初始快照由服务端一起建立水位，避免通知和读取之间的竞态丢失待回答问题。 */
  observe(
    input: MethodParams<"interaction/observe">,
  ): Promise<MethodResult<"interaction/observe">> {
    return invokeInteraction(
      this.bridge,
      JA_INTERACTION_COMMANDS.observe,
      "interaction/observe",
      input,
    );
  }

  /** 仅释放当前连接的观察句柄，保留问题状态供刷新、重连和其它窗口继续恢复。 */
  unobserve(
    input: MethodParams<"interaction/unobserve">,
  ): Promise<MethodResult<"interaction/unobserve">> {
    return invokeInteraction(
      this.bridge,
      JA_INTERACTION_COMMANDS.unobserve,
      "interaction/unobserve",
      input,
    );
  }

  /** 持久化可恢复草稿，所有答案仍经过 JA-RPC schema 校验。 */
  draftSave(
    input: MethodParams<"interaction/draft/save">,
  ): Promise<MethodResult<"interaction/draft/save">> {
    return invokeInteraction(
      this.bridge,
      JA_INTERACTION_COMMANDS.draftSave,
      "interaction/draft/save",
      input,
    );
  }

  /** 用 request revision 做 CAS，并由服务端在同一事务中结算恢复，避免重复提交重复唤醒模型。 */
  respond(
    input: MethodParams<"interaction/respond">,
  ): Promise<MethodResult<"interaction/respond">> {
    return invokeInteraction(
      this.bridge,
      JA_INTERACTION_COMMANDS.respond,
      "interaction/respond",
      input,
    );
  }

  /** 将取消作为独立终态写入服务端；收起、关闭或断线都不会被误解释成用户回答。 */
  cancel(input: MethodParams<"interaction/cancel">): Promise<MethodResult<"interaction/cancel">> {
    return invokeInteraction(
      this.bridge,
      JA_INTERACTION_COMMANDS.cancel,
      "interaction/cancel",
      input,
    );
  }
}

/** 创建唯一生产 Interaction adapter，测试通过构造函数注入受控 bridge。 */
export function createInteractionAdapter(): InteractionAdapter {
  return new TauriInteractionAdapter();
}
