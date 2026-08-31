// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  createRuntimeHostAdapter,
  type RuntimeHostAdapter,
  type RuntimeSettingsParams as NativeRuntimeSettingsParams,
} from "@/api/tauri/runtime";
import type {
  RuntimeHostPort,
  RuntimeQuery,
  RuntimeSettingsMethod,
  RuntimeSettingsParams,
  RuntimeSettingsResult,
} from "../application/runtimePorts";

/**
 * 逐个映射冻结的 Settings 查询，让 application 自有的关联类型不反向依赖 JA-RPC schema。
 * TypeScript 无法按泛型参数收窄 indexed access，分支内的断言只跨越这一静态限制；原生
 * adapter 已在 IPC 边缘按具体 method 校验 params/result，不能借此打开通用 RPC tunnel。
 */
async function queryRuntime<M extends RuntimeSettingsMethod>(
  adapter: RuntimeHostAdapter,
  method: M,
  params: RuntimeSettingsParams<M>,
): Promise<RuntimeSettingsResult<M>> {
  switch (method) {
    case "skill/list":
      return (await adapter.query(
        "skill/list",
        params as NativeRuntimeSettingsParams<"skill/list">,
      )) as RuntimeSettingsResult<M>;
    case "mcp/list":
      return (await adapter.query(
        "mcp/list",
        params as NativeRuntimeSettingsParams<"mcp/list">,
      )) as RuntimeSettingsResult<M>;
    case "mcp/test":
      return (await adapter.query(
        "mcp/test",
        params as NativeRuntimeSettingsParams<"mcp/test">,
      )) as RuntimeSettingsResult<M>;
    case "model/test":
      return (await adapter.query(
        "model/test",
        params as NativeRuntimeSettingsParams<"model/test">,
      )) as RuntimeSettingsResult<M>;
    case "mcp/list-tools":
      return (await adapter.query(
        "mcp/list-tools",
        params as NativeRuntimeSettingsParams<"mcp/list-tools">,
      )) as RuntimeSettingsResult<M>;
  }
}

/**
 * 在唯一 composition 边界把 Tauri Runtime adapter 投影为 application port。所有方法都
 * 保留原 adapter 的校验、订阅与错误语义，同时阻止 Provider 直接知道 Tauri 工厂。
 */
function createRuntimeHostPort(
  adapter: RuntimeHostAdapter = createRuntimeHostAdapter(),
): RuntimeHostPort {
  const query: RuntimeQuery = (method, params) => queryRuntime(adapter, method, params);
  return {
    start: () => adapter.start(),
    stop: () => adapter.stop(),
    state: () => adapter.state(),
    storageInfo: () => adapter.storageInfo(),
    generalWorkspace: () => adapter.generalWorkspace(),
    recoveryState: () => adapter.recoveryState(),
    acknowledgeRecovery: (confirmation) => adapter.acknowledgeRecovery(confirmation),
    approvalRespond: (input) => adapter.approvalRespond(input),
    turnStart: (input) => adapter.turnStart(input),
    turnCancel: (input) => adapter.turnCancel(input),
    turnSteer: (input) => adapter.turnSteer(input),
    turnFollowUp: (input) => adapter.turnFollowUp(input),
    query,
    subscribe: (listener) => adapter.subscribe(listener),
  };
}

/** 生产 Runtime port 在 composition 中创建一次，避免 React 重渲染重置订阅 identity。 */
export const DEFAULT_RUNTIME_HOST_PORT: RuntimeHostPort = createRuntimeHostPort();
