// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
/* eslint-disable react-refresh/only-export-components -- Provider 保留三个窄 hooks 的稳定公开入口。 */

import { useMemo, type PropsWithChildren, type ReactElement } from "react";
import {
  useRuntimeLifecycleController,
  type RuntimeLifecycleController,
  type RuntimeStateController,
} from "./application/useRuntimeLifecycleController";
import {
  RuntimeLifecycleContext,
  RuntimeStateContext,
  RuntimeTurnsContext,
  type RuntimeTurnsContract,
} from "./application/runtimeContexts";
import type { RuntimeHostPort, RuntimeProjectionPort } from "./application/runtimePorts";
export {
  useRuntimeLifecycle,
  useRuntimeState,
  useRuntimeTurns,
} from "./application/runtimeContexts";
export interface RuntimeProviderProps extends PropsWithChildren {
  readonly runtime: RuntimeHostPort;
  readonly projection: RuntimeProjectionPort;
}

/**
 * Provider 只组合 Runtime host/projection ports、lifecycle controller 与 Turn controller，并
 * 通过三个窄 Context 注入；视图读取状态时无法顺手获得进程、Store 或 Turn mutation 能力。
 */
export function RuntimeProvider({
  runtime,
  projection,
  children,
}: RuntimeProviderProps): ReactElement {
  const controllers = useRuntimeLifecycleController(runtime, projection);
  const state = useMemo<RuntimeStateController>(
    () => ({
      boot: controllers.state.boot,
      lastEvent: controllers.state.lastEvent,
      lastThreadMetadataEvent: controllers.state.lastThreadMetadataEvent,
      recovery: controllers.state.recovery,
      runtimeState: controllers.state.runtimeState,
      turnAdmissionReady: controllers.state.turnAdmissionReady,
    }),
    [
      controllers.state.boot,
      controllers.state.lastEvent,
      controllers.state.lastThreadMetadataEvent,
      controllers.state.recovery,
      controllers.state.runtimeState,
      controllers.state.turnAdmissionReady,
    ],
  );
  const lifecycle = useMemo<RuntimeLifecycleController>(
    () => ({
      acknowledgeRecovery: controllers.lifecycle.acknowledgeRecovery,
      generalWorkspace: controllers.lifecycle.generalWorkspace,
      queryRuntime: controllers.lifecycle.queryRuntime,
      readRuntimeStorage: controllers.lifecycle.readRuntimeStorage,
      startRuntime: controllers.lifecycle.startRuntime,
      stop: controllers.lifecycle.stop,
    }),
    [
      controllers.lifecycle.acknowledgeRecovery,
      controllers.lifecycle.generalWorkspace,
      controllers.lifecycle.queryRuntime,
      controllers.lifecycle.readRuntimeStorage,
      controllers.lifecycle.startRuntime,
      controllers.lifecycle.stop,
    ],
  );
  const turns = useMemo<RuntimeTurnsContract>(
    () => ({
      approvalRespond: controllers.turns.approvalRespond,
      cancelTurn: controllers.turns.cancelTurn,
      followUpTurn: controllers.turns.followUpTurn,
      steerTurn: controllers.turns.steerTurn,
      submitTurn: controllers.turns.submitTurn,
    }),
    [
      controllers.turns.approvalRespond,
      controllers.turns.cancelTurn,
      controllers.turns.followUpTurn,
      controllers.turns.steerTurn,
      controllers.turns.submitTurn,
    ],
  );

  return (
    <RuntimeStateContext.Provider value={state}>
      <RuntimeLifecycleContext.Provider value={lifecycle}>
        <RuntimeTurnsContext.Provider value={turns}>{children}</RuntimeTurnsContext.Provider>
      </RuntimeLifecycleContext.Provider>
    </RuntimeStateContext.Provider>
  );
}
