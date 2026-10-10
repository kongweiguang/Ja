// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import {
  parseMethodParams,
  parseMethodResult,
  type ClientMethod,
  type MethodParams,
  type MethodResult,
} from "../protocol/methods";
import {
  defaultNativeBridge,
  normalizeRuntimeError,
  RuntimeHostError,
  type RuntimeNativeBridge,
} from "./runtime";

type ProcessMethod = Extract<
  ClientMethod,
  "process/list" | "process/read" | "process/write" | "process/stop"
>;

export const JA_PROCESS_COMMANDS = {
  list: "ja_process_list",
  read: "ja_process_read",
  write: "ja_process_write",
  stop: "ja_process_stop",
} as const;

export interface ProcessSessionAdapter {
  list(input: MethodParams<"process/list">): Promise<MethodResult<"process/list">>;
  read(input: MethodParams<"process/read">): Promise<MethodResult<"process/read">>;
  write(input: MethodParams<"process/write">): Promise<MethodResult<"process/write">>;
  stop(input: MethodParams<"process/stop">): Promise<MethodResult<"process/stop">>;
}

/** command 固定映射 JA-RPC 方法，Renderer 不得提交通用 method 或 command 名。 */
async function invokeProcess<M extends ProcessMethod>(
  bridge: RuntimeNativeBridge,
  command: (typeof JA_PROCESS_COMMANDS)[keyof typeof JA_PROCESS_COMMANDS],
  method: M,
  input: MethodParams<M>,
): Promise<MethodResult<M>> {
  let parsed: MethodParams<M>;
  try {
    parsed = parseMethodParams(method, input);
  } catch {
    throw new RuntimeHostError("INVALID_INPUT", "进程会话请求参数无效。", false);
  }
  try {
    const result = await bridge.invoke<unknown>(command, { input: parsed });
    return parseMethodResult(method, result);
  } catch (error) {
    if (error instanceof z.ZodError)
      throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时返回的数据无效。", true);
    throw normalizeRuntimeError(error);
  }
}

/** 每个 process command 都由 JA-RPC v2 方法 Schema 收敛，写入与停止仍由 Java 持有唯一状态。 */
export class TauriProcessSessionAdapter implements ProcessSessionAdapter {
  constructor(private readonly bridge: RuntimeNativeBridge = defaultNativeBridge) {}

  /** 列表不带客户端代际；服务端返回 registry fence 给后续单会话请求使用。 */
  list(input: MethodParams<"process/list">) {
    return invokeProcess(this.bridge, JA_PROCESS_COMMANDS.list, "process/list", input);
  }

  /** 读取输入代际 fence 并返回绑定进程原始 generation 的输出快照。 */
  read(input: MethodParams<"process/read">) {
    return invokeProcess(this.bridge, JA_PROCESS_COMMANDS.read, "process/read", input);
  }

  /** stdin 操作保持上层 operationKey；不确定 ACK 时调用方必须以相同身份重试。 */
  write(input: MethodParams<"process/write">) {
    return invokeProcess(this.bridge, JA_PROCESS_COMMANDS.write, "process/write", input);
  }

  /** stop 仅确认停止请求，实际退出状态与退出码以后续 read 快照为准。 */
  stop(input: MethodParams<"process/stop">) {
    return invokeProcess(this.bridge, JA_PROCESS_COMMANDS.stop, "process/stop", input);
  }
}

/** Composition root 复用单一固定 command adapter。 */
export function createProcessSessionAdapter(): ProcessSessionAdapter {
  return new TauriProcessSessionAdapter();
}
