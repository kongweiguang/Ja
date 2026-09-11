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
import { publishTaskHostEvent } from "@/features/tasks";
import { publishGoalHostEvent } from "@/features/goals";
import { publishInteractionHostEvent, useTimelineStore } from "@/features/conversation";

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
    case "workspace/path/search":
      return (await adapter.query(
        "workspace/path/search",
        params as NativeRuntimeSettingsParams<"workspace/path/search">,
      )) as RuntimeSettingsResult<M>;
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
export function createRuntimeHostPort(
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
    turnResume: (input) => adapter.turnResume(input),
    turnCancel: (input) => adapter.turnCancel(input),
    turnInputEnqueue: (input) => adapter.turnInputEnqueue(input),
    turnInputPrioritize: (input) => adapter.turnInputPrioritize(input),
    turnInputUpdate: (input) => adapter.turnInputUpdate(input),
    turnInputDelete: (input) => adapter.turnInputDelete(input),
    query,
    /** Task notification 进入独立 feature bus；Conversation lifecycle 永远只看到自身闭集。 */
    subscribe: (listener) =>
      adapter.subscribe((event) => {
        if (event.kind === "task") {
          publishTaskHostEvent(event.event);
          return;
        }
        if (event.kind === "interaction") {
          const params = event.event.params;
          // 取消问题在同一事务关闭挂起 Turn；仅标记对应会话回读，不在这里伪造终态或扫描隐藏会话。
          if (params.kind === "cancelled") {
            useTimelineStore.getState().requestThreadResync(params.threadId);
          }
          publishInteractionHostEvent({
            kind:
              params.kind === "answered"
                ? "answered"
                : params.kind === "cancelled"
                  ? "cancelled"
                  : "snapshot_changed",
            threadId: params.threadId,
            eventSequence: params.eventSequence,
          });
          return;
        }
        if (event.kind === "plan") {
          const params = event.event.params;
          publishGoalHostEvent({
            method: "plan/changed",
            planId: params.planId,
            ownerThreadId: params.ownerThreadId,
            planRevision: params.planRevision,
            planEventSequence: params.eventSequence,
            plan: { ...params.plan, ownerThreadId: params.plan.owner.threadId },
            progress: params.progress,
            goalRevision: 0,
            eventSequence: params.eventSequence,
            occurredAt: params.occurredAt,
          });
          return;
        }
        if (event.kind === "goal") {
          const params = event.event.params;
          const ownerThreadId =
            event.event.method === "goal/changed" && event.event.params.goal.owner.kind === "thread"
              ? event.event.params.goal.owner.threadId
              : undefined;
          publishGoalHostEvent({
            method: event.event.method,
            goalId: params.goalId,
            goalRevision: params.goalRevision,
            eventSequence: params.eventSequence,
            occurredAt: params.occurredAt,
            ...(ownerThreadId === undefined ? {} : { ownerThreadId }),
          });
          return;
        }
        // Native adapter 与 Conversation domain 的结构由各自合同测试锁定；这里仅跨越 TS
        // 对已投影附件字段的静态差异，Task 分支已在上方被彻底移除。
        listener(event as Parameters<typeof listener>[0]);
      }),
  };
}

/** 生产 Runtime port 在 composition 中创建一次，避免 React 重渲染重置订阅 identity。 */
export const DEFAULT_RUNTIME_HOST_PORT: RuntimeHostPort = createRuntimeHostPort();
