// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState } from "react";
import {
  normalizeRuntimeApplicationError as normalizeRuntimeError,
  RuntimeApplicationError as RuntimeHostError,
  type ManualRecoveryConfirmation,
  type RecoveryReason,
  type RuntimeHostPort,
  type RuntimeHostEvent,
  type RuntimeProjectionPort,
  type GeneralWorkspace,
  type RuntimeRecoveryState,
  type RuntimeStatus,
  type RuntimeQuery,
  type RuntimeSettingsMethod,
  type RuntimeSettingsParams,
  type RuntimeSettingsResult,
  type RuntimeStorageInfo,
} from "./runtimePorts";
import type { BootState } from "../bootState";
import {
  useRuntimeTurnController,
  type RuntimePendingOperation,
  type RuntimeTurnController,
} from "./useRuntimeTurnController";

type RuntimeTimelineHostEvent = Extract<RuntimeHostEvent, { kind: "timeline" }>;
type RuntimeThreadMetadataEvent = Extract<
  RuntimeTimelineHostEvent["event"],
  { method: "thread/metadata-changed" }
>;

export interface RuntimeStateController {
  readonly boot: BootState;
  /** 仅当活动 generation 通过 native ready-state admission fence 后为 true，避免 UI 提前放行 Turn。 */
  readonly turnAdmissionReady: boolean;
  readonly runtimeState: RuntimeStatus | undefined;
  readonly recovery: RuntimeRecoveryState | undefined;
  readonly lastEvent: RuntimeHostEvent | undefined;
  /**
   * 标题事件拥有独立投影，避免紧随其后的 Turn/流式事件在 React 批处理中覆盖唯一刷新信号。
   */
  readonly lastThreadMetadataEvent: RuntimeThreadMetadataEvent | undefined;
}

export interface RuntimeLifecycleController {
  /** 启动 sidecar 时不接收配置 snapshot；配置错误属于 server state，不能由 React 判定。 */
  readonly startRuntime: () => Promise<RuntimeStatus>;
  /** 读取 native 拥有的固定 scope，供无项目会话使用，React 不生成 workspace identity。 */
  readonly generalWorkspace: () => Promise<GeneralWorkspace>;
  readonly stop: () => Promise<RuntimeStatus>;
  /** 通过当前 Ready generation 读取固定 Skills/MCP 设置面，防止绕过 Runtime owner。 */
  readonly queryRuntime: RuntimeQuery;
  readonly readRuntimeStorage: () => Promise<RuntimeStorageInfo>;
  readonly acknowledgeRecovery: (reason: RecoveryReason) => Promise<RuntimeRecoveryState>;
}

export interface RuntimeControllers {
  readonly state: RuntimeStateController;
  readonly lifecycle: RuntimeLifecycleController;
  readonly turns: RuntimeTurnController;
}

interface TurnAdmissionGate {
  lifecycleEpoch: number;
  generation: number;
  serverInstanceId: string;
  runtime: RuntimeHostPort;
}

/** 将 native state 映射到 UI state，同时不向 WebView 暴露进程诊断。 */
function bootForStatus(status: RuntimeStatus): BootState {
  switch (status.status) {
    case "ready":
      return { status: "ready" };
    case "busy":
      return { status: "busy" };
    case "recovery_required":
      return { status: "recovery_required" };
    case "stopped":
      return { status: "stopped" };
    case "starting":
    case "stopping":
      return { status: "connecting" };
    case "crashed":
    case "incompatible":
    case "faulted":
      return { status: "degraded", message: "运行时需要重新启动" };
  }
}

/** 通过注入端口只投影正 generation，防止 controller 知道 Store 或被 generation zero 错误解锁。 */
function stateProjection(
  projection: RuntimeProjectionPort,
  status: RuntimeStatus,
  eventId?: string,
  occurredAt?: string,
  reason?: string,
): void {
  const currentGeneration = projection.currentGeneration();
  const generation =
    status.generation > 0
      ? status.generation
      : status.status === "stopped" && currentGeneration > 0
        ? currentGeneration
        : undefined;
  if (generation === undefined) {
    return;
  }
  projection.applyRuntimeStatus({
    status: status.status,
    generation,
    serverInstanceId: status.serverInstanceId,
    eventId,
    occurredAt,
    reason,
  });
}

/** 判断 status 是否仍属于活动 sidecar generation，拒绝旧 generation 回写。 */
function statusBelongsToCurrentGeneration(
  status: RuntimeStatus,
  current: RuntimeStatus | undefined,
): boolean {
  if (current === undefined) {
    return true;
  }
  if (status.generation === 0) {
    return current.generation === 0;
  }
  return current.generation === 0 || status.generation >= current.generation;
}

/**
 * 将启动 admission 限定为 identity 完整的 native ready state；generation zero 或缺失
 * server identity 都不能授权 turn/start。
 */
function isReadyStatus(
  status: RuntimeStatus,
): status is RuntimeStatus & { status: "ready"; serverInstanceId: string } {
  return (
    status.status === "ready" &&
    status.generation > 0 &&
    typeof status.serverInstanceId === "string"
  );
}

/**
 * 仅当两个 lifecycle identity 一致时接纳启动 snapshot。start reply 证明 generation 已创建，
 * 随后的 state query 则在放行 turn/start 前证明 actor 仍拥有同一 generation。
 */
function sameReadyGeneration(started: RuntimeStatus, observed: RuntimeStatus): boolean {
  return (
    isReadyStatus(started) &&
    isReadyStatus(observed) &&
    observed.generation === started.generation &&
    observed.serverInstanceId === started.serverInstanceId
  );
}

/** 将任意 adapter failure 收敛为稳定公开错误契约，避免内部诊断穿透 Provider。 */
function safeError(error: unknown): RuntimeHostError {
  return normalizeRuntimeError(error);
}

/**
 * lifecycle controller 统一拥有 sidecar 启停、恢复、串行队列与 Turn admission gate；
 * Turn/Approval 用例委托独立 controller。operation epoch 是线性化点，晚结果可以完成原调用方
 * Promise，但不能覆盖更新 intent；Timeline 仅通过注入 projection port 发布，本层不读取
 * Workspace、Settings 或 Conversation Store。
 */
export function useRuntimeLifecycleController(
  runtime: RuntimeHostPort,
  projection: RuntimeProjectionPort,
): RuntimeControllers {
  const [boot, setBoot] = useState<BootState>({ status: "idle" });
  const [turnAdmissionReady, setTurnAdmissionReady] = useState(false);
  const [runtimeState, setRuntimeState] = useState<RuntimeStatus>();
  const [recovery, setRecovery] = useState<RuntimeRecoveryState>();
  const [lastEvent, setLastEvent] = useState<RuntimeHostEvent>();
  const [lastThreadMetadataEvent, setLastThreadMetadataEvent] =
    useState<RuntimeThreadMetadataEvent>();

  const bootRef = useRef<BootState>({ status: "idle" });
  const runtimeStateRef = useRef<RuntimeStatus | undefined>(undefined);
  const recoveryRef = useRef<RuntimeRecoveryState | undefined>(undefined);
  const lifecycleEpochRef = useRef(0);
  const operationEpochRef = useRef(0);
  const serialQueueRef = useRef<Promise<void>>(Promise.resolve());
  const inFlightRef = useRef<Map<string, RuntimePendingOperation<unknown>>>(new Map());
  const cleanupRef = useRef<Promise<void>>(Promise.resolve());
  const lifecycleActiveRef = useRef(false);
  const turnGateRef = useRef<TurnAdmissionGate | undefined>(undefined);
  const turnAdmissionReadyRef = useRef(false);
  const configurationIntentRef = useRef(0);

  /** 同步更新 React state 与 guard ref，避免并发 continuation 读取尚未 commit 的 lifecycle 值。 */
  const updateBoot = useCallback((next: BootState): void => {
    bootRef.current = next;
    setBoot(next);
  }, []);

  /** 同步保存 Runtime state，让刷新 guard 不依赖尚未 commit 的 render。 */
  const updateRuntimeState = useCallback((next: RuntimeStatus): void => {
    runtimeStateRef.current = next;
    setRuntimeState(next);
  }, []);

  /** 同步保存 recovery state，使重复点击的 admission 检查共享同一权威值。 */
  const updateRecovery = useCallback((next: RuntimeRecoveryState): void => {
    recoveryRef.current = next;
    setRecovery(next);
  }, []);

  /** 对齐可渲染 admission signal 与同步 gate，避免视图和命令路径状态分叉。 */
  const updateTurnAdmissionReady = useCallback((next: boolean): void => {
    turnAdmissionReadyRef.current = next;
    setTurnAdmissionReady(next);
  }, []);

  /**
   * 在每个 lifecycle boundary 撤销 Turn admission；旧 generation 成功过也不能在
   * configure/stop/recovery 后继续授权工作。
   */
  const revokeTurnGate = useCallback((): void => {
    turnGateRef.current = undefined;
    updateTurnAdmissionReady(false);
  }, [updateTurnAdmissionReady]);

  /**
   * 检查 coding work 的窄 admission 契约。gate 与 typed context 同属 Provider，
   * 调用方无法通过 generic runtime 或旧 startTurn 绕过配置与 generation 校验。
   */
  const isTurnGenerationCurrent = useCallback(
    (lifecycleEpoch: number, generation: number): boolean => {
      const gate = turnGateRef.current;
      return (
        gate !== undefined &&
        turnAdmissionReadyRef.current &&
        gate.runtime === runtime &&
        gate.lifecycleEpoch === lifecycleEpoch &&
        gate.generation === generation &&
        lifecycleEpochRef.current === lifecycleEpoch &&
        runtimeStateRef.current?.generation === generation &&
        runtimeStateRef.current.serverInstanceId === gate.serverInstanceId
      );
    },
    [runtime],
  );

  /** 新 Turn admission 还必须处于 ready；已经运行的 Turn 可以把状态投影为 busy。 */
  const isTurnGateCurrent = useCallback(
    (lifecycleEpoch: number, generation: number): boolean =>
      isTurnGenerationCurrent(lifecycleEpoch, generation) && bootRef.current.status === "ready",
    [isTurnGenerationCurrent],
  );

  /** 把 native call 放入唯一串行队列，并按 key 合并相同 in-flight intent。 */
  const enqueueOperation = useCallback(
    <T>(key: string, operation: () => Promise<T>): RuntimePendingOperation<T> => {
      const existing = inFlightRef.current.get(key);
      if (existing !== undefined) {
        return existing as RuntimePendingOperation<T>;
      }

      const epoch = operationEpochRef.current + 1;
      operationEpochRef.current = epoch;
      const execution = serialQueueRef.current.catch(() => undefined).then(operation);
      const settled = execution.finally(() => {
        const current = inFlightRef.current.get(key);
        if (current?.epoch === epoch) {
          inFlightRef.current.delete(key);
        }
      });
      const pending: RuntimePendingOperation<T> = { epoch, promise: settled };
      inFlightRef.current.set(key, pending as RuntimePendingOperation<unknown>);
      // 队列内部消费 failure，避免一次 rejected command 污染后续命令；原调用方仍收到原始 rejection。
      serialQueueRef.current = settled.then(
        () => undefined,
        () => undefined,
      );
      return pending;
    },
    [],
  );

  const turns = useRuntimeTurnController({
    runtime,
    projection,
    lifecycleEpochRef,
    runtimeStateRef,
    recoveryRef,
    bootRef,
    enqueueOperation,
    isTurnGateCurrent,
    isTurnGenerationCurrent,
  });
  const applyTurnHostEvent = turns.applyHostEvent;

  /** 仅当 lifecycle 与 operation epoch 同时匹配时，结果才属于当前 intent。 */
  const isCurrentOperation = useCallback(
    (operationEpoch: number, lifecycleEpoch: number): boolean =>
      operationEpochRef.current === operationEpoch && lifecycleEpochRef.current === lifecycleEpoch,
    [],
  );

  /** 仅在没有更新 native intent 取代时提交 operation result。 */
  const commitStatus = useCallback(
    (
      status: RuntimeStatus,
      operationEpoch: number,
      lifecycleEpoch: number,
      eventId?: string,
      occurredAt?: string,
      reason?: string,
    ): boolean => {
      if (!isCurrentOperation(operationEpoch, lifecycleEpoch)) {
        return false;
      }
      const gate = turnGateRef.current;
      if (
        gate !== undefined &&
        (gate.lifecycleEpoch !== lifecycleEpoch ||
          gate.runtime !== runtime ||
          gate.generation !== status.generation ||
          !["ready", "busy"].includes(status.status))
      ) {
        revokeTurnGate();
      }
      updateRuntimeState(status);
      stateProjection(projection, status, eventId, occurredAt, reason);
      updateBoot(bootForStatus(status));
      return true;
    },
    [isCurrentOperation, projection, revokeTurnGate, runtime, updateBoot, updateRuntimeState],
  );

  /**
   * 按 lifecycle ownership 提交已经 state-confirmed 的 configure/start 结果，而不是依赖
   * generic operation epoch。排在 startup 后的只读调用不能让 Rust 刚确认的 generation
   * 失效；stop、reconfigure 与 unmount 仍会使此 ownership gate 失败。
   */
  const commitConfiguredReady = useCallback(
    (status: RuntimeStatus, lifecycleEpoch: number, configurationIntent: number): boolean => {
      if (
        !lifecycleActiveRef.current ||
        lifecycleEpochRef.current !== lifecycleEpoch ||
        configurationIntentRef.current !== configurationIntent ||
        inFlightRef.current.has("stop") ||
        !isReadyStatus(status)
      ) {
        return false;
      }
      updateRuntimeState(status);
      stateProjection(projection, status);
      updateBoot({ status: "ready" });
      turnGateRef.current = {
        lifecycleEpoch,
        generation: status.generation,
        serverInstanceId: status.serverInstanceId,
        runtime,
      };
      updateTurnAdmissionReady(true);
      return true;
    },
    [projection, runtime, updateBoot, updateRuntimeState, updateTurnAdmissionReady],
  );

  /** 通过与命令相同的串行 lane 读取 authoritative host state，保持全序。 */
  const refreshState = useCallback(
    (lifecycleEpoch: number): Promise<RuntimeStatus> => {
      const pending = enqueueOperation("state", () => runtime.state());
      return pending.promise
        .then((status) => {
          commitStatus(status, pending.epoch, lifecycleEpoch);
          return status;
        })
        .catch((error: unknown) => {
          const normalized = safeError(error);
          if (isCurrentOperation(pending.epoch, lifecycleEpoch)) {
            updateBoot({ status: "failed", message: normalized.message });
          }
          throw normalized;
        });
    },
    [commitStatus, enqueueOperation, isCurrentOperation, runtime, updateBoot],
  );

  /**
   * 启动一个不接收配置数据的 sidecar generation。app-server 自行读取 home/config/auth，
   * 缺失或非法配置表现为 Turn admission error，而不是 React 推导的 boot error。
   */
  const startRuntime = useCallback((): Promise<RuntimeStatus> => {
    const lifecycleEpoch = lifecycleEpochRef.current;
    revokeTurnGate();
    if (recoveryRef.current?.required === true || bootRef.current.status === "recovery_required") {
      return Promise.reject(
        new RuntimeHostError("RECOVERY_REQUIRED", "需要先完成运行时恢复", false),
      );
    }
    updateBoot({ status: "connecting" });
    const operationKey = "startRuntime";
    const duplicate = inFlightRef.current.has(operationKey);
    const configurationIntent = duplicate
      ? configurationIntentRef.current
      : configurationIntentRef.current + 1;
    if (!duplicate) {
      configurationIntentRef.current = configurationIntent;
    }
    const pending = enqueueOperation(operationKey, async () => {
      if (
        configurationIntentRef.current !== configurationIntent ||
        inFlightRef.current.has("stop")
      ) {
        throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时启动已取消", true);
      }
      const started = await runtime.start();
      if (!isReadyStatus(started)) {
        throw new RuntimeHostError("RUNTIME_NOT_READY", "运行时未达到可接收对话的就绪状态", true);
      }
      // admission fence 必须留在同一串行 lifecycle lane。UI 可以先观察到 ready event，
      // 但 native host 确认精确 generation 与 server owner 前不能取得 Turn gate。
      const observed = await runtime.state();
      if (!sameReadyGeneration(started, observed)) {
        throw new RuntimeHostError("RUNTIME_NOT_READY", "运行时就绪状态尚未确认，请重试", true);
      }
      if (
        configurationIntentRef.current !== configurationIntent ||
        inFlightRef.current.has("stop")
      ) {
        throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时启动已取消", true);
      }
      return observed;
    });
    const startupResult = pending.promise
      .then((status) => {
        const startupLifecycleEpoch =
          lifecycleActiveRef.current &&
          configurationIntentRef.current === configurationIntent &&
          !inFlightRef.current.has("stop")
            ? lifecycleEpochRef.current
            : lifecycleEpoch;
        if (!commitConfiguredReady(status, startupLifecycleEpoch, configurationIntent)) {
          throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时启动已取消", true);
        }
        return status;
      })
      .catch((error: unknown) => {
        const normalized = safeError(error);
        if (isCurrentOperation(pending.epoch, lifecycleEpoch)) {
          updateBoot(
            normalized.code === "RECOVERY_REQUIRED"
              ? { status: "recovery_required" }
              : { status: "failed", message: normalized.message },
          );
        }
        throw normalized;
      });
    return startupResult;
  }, [
    commitConfiguredReady,
    enqueueOperation,
    isCurrentOperation,
    revokeTurnGate,
    runtime,
    updateBoot,
  ]);

  /**
   * 只暴露 typed native general-workspace projection。它复用 Provider 串行 lane，
   * StrictMode 或并发 settings refresh 无法在 lifecycle owner 外发出第二次 raw native call。
   */
  const generalWorkspace = useCallback((): Promise<GeneralWorkspace> => {
    const pending = enqueueOperation("generalWorkspace", () => runtime.generalWorkspace());
    return pending.promise.catch((error: unknown) => {
      throw safeError(error);
    });
  }, [enqueueOperation, runtime]);

  /** 通过 start 共用的串行 lane 停止当前 sidecar，避免 start/stop 交错。 */
  const stop = useCallback((): Promise<RuntimeStatus> => {
    const lifecycleEpoch = lifecycleEpochRef.current;
    revokeTurnGate();
    updateBoot({ status: "connecting" });
    const pending = enqueueOperation("stop", () => runtime.stop());
    return pending.promise
      .then((status) => {
        commitStatus(status, pending.epoch, lifecycleEpoch);
        return status;
      })
      .catch((error: unknown) => {
        const normalized = safeError(error);
        if (isCurrentOperation(pending.epoch, lifecycleEpoch)) {
          updateBoot({ status: "failed", message: normalized.message });
        }
        throw normalized;
      });
  }, [commitStatus, enqueueOperation, isCurrentOperation, revokeTurnGate, runtime, updateBoot]);

  /**
   * 将 settings query 与 lifecycle operation 串行化，并在 Ready generation 变化时拒绝。
   * 这样无需在 frontend 创建第二个 RPC registry，也能阻止旧 MCP/Skills projection
   * 在 sidecar reconfigure 后写入。
   */
  const queryRuntime: RuntimeQuery = useCallback(
    <M extends RuntimeSettingsMethod>(method: M, params: RuntimeSettingsParams<M>) => {
      const generation = runtimeStateRef.current?.generation;
      if (generation === undefined || generation <= 0 || bootRef.current.status !== "ready") {
        return Promise.reject(
          new RuntimeHostError("RUNTIME_NOT_READY", "运行时尚未完成配置", true),
        );
      }
      // 此设置面的 params 只包含 bounded revision；key 用于合并重复点击，绝不保留 endpoint 或 Secret。
      const queryKey = `runtimeQuery:${method}:${JSON.stringify(params)}`;
      const pending = enqueueOperation<RuntimeSettingsResult<M>>(queryKey, () =>
        runtime.query(method, params),
      );
      return pending.promise
        .then((result) => {
          if (
            runtimeStateRef.current?.generation !== generation ||
            bootRef.current.status !== "ready"
          ) {
            throw new RuntimeHostError("RUNTIME_NOT_READY", "运行时状态已变化，请重试", true);
          }
          return result;
        })
        .catch((error: unknown) => {
          throw safeError(error);
        });
    },
    [enqueueOperation, runtime],
  );

  /**
   * 通过同一 operation queue 读取 native storage projection，防止 shutdown 与旧 settings
   * update 竞态并把过期结果写入当前页面。
   */
  const readRuntimeStorage = useCallback((): Promise<RuntimeStorageInfo> => {
    const pending = enqueueOperation("runtimeStorage", () => runtime.storageInfo());
    return pending.promise.catch((error: unknown) => {
      throw safeError(error);
    });
  }, [enqueueOperation, runtime]);

  /** 只确认用户实际看到的 recovery revision，拒绝替更新恢复事实代签。 */
  const acknowledgeRecovery = useCallback(
    (reason: RecoveryReason): Promise<RuntimeRecoveryState> => {
      const lifecycleEpoch = lifecycleEpochRef.current;
      revokeTurnGate();
      const current = recoveryRef.current;
      if (
        current?.required !== true ||
        current.recoveryId === null ||
        current.recoveryId === undefined ||
        current.revision === null ||
        current.revision === undefined
      ) {
        return Promise.reject(
          new RuntimeHostError("RECOVERY_REQUIRED", "需要先读取运行时恢复状态", false),
        );
      }
      const confirmation: ManualRecoveryConfirmation = {
        recoveryId: current.recoveryId,
        revision: current.revision,
        reason,
      };
      updateBoot({ status: "connecting" });
      const pending = enqueueOperation("acknowledgeRecovery", () =>
        runtime.acknowledgeRecovery(confirmation),
      );
      return pending.promise
        .then((next) => {
          if (isCurrentOperation(pending.epoch, lifecycleEpoch)) {
            updateRecovery(next);
            updateBoot(next.required ? { status: "recovery_required" } : { status: "stopped" });
          }
          return next;
        })
        .catch((error: unknown) => {
          const normalized = safeError(error);
          if (isCurrentOperation(pending.epoch, lifecycleEpoch)) {
            updateBoot({ status: "recovery_required" });
          }
          throw normalized;
        });
    },
    [enqueueOperation, isCurrentOperation, revokeTurnGate, runtime, updateBoot, updateRecovery],
  );

  useEffect(() => {
    const lifecycleEpoch = lifecycleEpochRef.current + 1;
    lifecycleEpochRef.current = lifecycleEpoch;
    lifecycleActiveRef.current = true;
    let active = true;
    let unsubscribe: (() => void | Promise<void>) | undefined;

    /** 仅在 subscription generation 仍为当前 owner 时应用 event，晚事件直接失效。 */
    const handleEvent = (event: RuntimeHostEvent): void => {
      if (!active || lifecycleEpochRef.current !== lifecycleEpoch) {
        return;
      }
      if (event.kind === "status") {
        // 必须先撤销再过滤 generation：stop/recovery projection 可有意使用 generation zero，
        // 但仍应使旧 Turn 失效。
        const gate = turnGateRef.current;
        // event delivery 与 command response 使用不同 native channel，因此排队中的 lifecycle
        // boundary status 可能晚于 start reply。generation-zero starting/stopped fact 属于已被
        // 取代的 host boundary，若当作当前事实会错误撤销已 state-confirmed 的 admission gate。
        const lateHostBoundaryStatus =
          event.status.generation === 0 &&
          (event.status.status === "starting" || event.status.status === "stopped");
        if (
          !lateHostBoundaryStatus &&
          gate !== undefined &&
          (gate.runtime !== runtime ||
            gate.lifecycleEpoch !== lifecycleEpoch ||
            gate.generation !== event.status.generation ||
            gate.serverInstanceId !== event.status.serverInstanceId ||
            !["ready", "busy"].includes(event.status.status))
        ) {
          revokeTurnGate();
        }
        const current = runtimeStateRef.current;
        const stopPending = inFlightRef.current.has("stop");
        // startRuntime 拥有 start command 的 operation key；旧 stopped event 不能覆盖正在 admission 的 generation。
        const startPending = inFlightRef.current.has("startRuntime");
        if (
          (stopPending && ["starting", "ready", "busy"].includes(event.status.status)) ||
          (startPending && event.status.status === "stopped") ||
          !statusBelongsToCurrentGeneration(event.status, current)
        ) {
          return;
        }
        setLastEvent(event);
        updateRuntimeState(event.status);
        stateProjection(projection, event.status, event.eventId, event.occurredAt, event.reason);
        updateBoot(bootForStatus(event.status));
        if (event.status.status === "recovery_required") {
          const pending = enqueueOperation("recoveryState", () => runtime.recoveryState());
          void pending.promise
            .then((next) => {
              if (
                active &&
                lifecycleEpochRef.current === lifecycleEpoch &&
                isCurrentOperation(pending.epoch, lifecycleEpoch)
              ) {
                updateRecovery(next);
                updateBoot({ status: "recovery_required" });
              }
            })
            .catch(() => undefined);
        }
        return;
      }

      // metadata 不能只借用 lastEvent 槽位：admission 后会立即继续发布 Turn 事件，React 可能
      // 合并同一批更新；独立保留最近标题 identity 才能保证侧栏、标题栏和搜索都至少消费一次。
      if (event.kind === "timeline" && event.event.method === "thread/metadata-changed") {
        setLastThreadMetadataEvent(event.event);
      }
      setLastEvent(event);
      if (applyTurnHostEvent(event)) {
        // completion 是外部通知；state query 必须入队，使显式 stop/start intent 始终胜过晚 completion。
        void refreshState(lifecycleEpoch).catch(() => undefined);
      }
    };

    /**
     * 恢复已有 native owner 或启动新 owner；只有相同 generation 与 server identity
     * 通过 state confirmation 后才接纳 Turn。
     */
    const setup = (async (): Promise<void> => {
      // 新 subscription 创建第二个进程或接收旧 generation event 前，必须等待 StrictMode cleanup。
      await cleanupRef.current.catch(() => undefined);
      if (!active || lifecycleEpochRef.current !== lifecycleEpoch) {
        return;
      }
      revokeTurnGate();
      updateBoot({ status: "idle" });
      try {
        const currentRecovery = await runtime.recoveryState();
        if (!active || lifecycleEpochRef.current !== lifecycleEpoch) {
          return;
        }
        updateRecovery(currentRecovery);
        unsubscribe = await runtime.subscribe(handleEvent);
        if (!active || lifecycleEpochRef.current !== lifecycleEpoch) {
          return;
        }
        if (currentRecovery.required) {
          updateBoot({ status: "recovery_required" });
          return;
        }

        const pending = enqueueOperation("state", () => runtime.state());
        const current = await pending.promise;
        if (
          !active ||
          lifecycleEpochRef.current !== lifecycleEpoch ||
          !isCurrentOperation(pending.epoch, lifecycleEpoch)
        ) {
          return;
        }
        commitStatus(current, pending.epoch, lifecycleEpoch);
        if (current.status === "stopped") {
          // Startup 与 config validity 解耦，使 Settings 能在 host 健康时加载并修复缺失或损坏的 Provider/Model。
          await startRuntime();
        } else if (
          isReadyStatus(current) &&
          !commitConfiguredReady(current, lifecycleEpoch, configurationIntentRef.current)
        ) {
          throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时就绪状态已失效，请重试", true);
        }
      } catch (error: unknown) {
        const normalized = safeError(error);
        if (!active || lifecycleEpochRef.current !== lifecycleEpoch) {
          return;
        }
        updateBoot(
          normalized.code === "RECOVERY_REQUIRED"
            ? { status: "recovery_required" }
            : { status: "failed", message: normalized.message },
        );
        // 保留已建立的 native subscription，让显式“重新启动”继续接收新 generation 事件。
        // Rust host 才是进程 owner，前端 setup 失败不能停止可能已被新 renderer 接管的 sidecar。
      }
    })();

    let cleanupPromise: Promise<void> | undefined;
    const cleanup = (): Promise<void> => {
      if (cleanupPromise !== undefined) {
        return cleanupPromise;
      }
      active = false;
      if (lifecycleEpochRef.current === lifecycleEpoch) {
        // 任意 await 前先把 owner 标为 inactive，避免真实 unmount 被误认成已有新 owner 的 StrictMode replay。
        lifecycleActiveRef.current = false;
      }
      // 同步使当前 owner 失效。cleanup 返回后 StrictMode replacement effect 可能立即启动；
      // 若到首次 await 后才递增，会错误使更新 owner 失效。
      lifecycleEpochRef.current += 1;
      cleanupPromise = (async (): Promise<void> => {
        // React StrictMode cleanup/replay 成对发生时保持 operation/configuration identity 稳定；
        // 真实 unmount 仍会在释放 host 前等待并补偿下方跟踪的 startup。
        revokeTurnGate();
        await setup.catch(() => undefined);
        if (unsubscribe !== undefined) {
          try {
            await unsubscribe();
          } catch {
            // cleanup 是 bounded best effort；native stop 仍是最终资源 owner。
          }
          unsubscribe = undefined;
        }
      })();
      cleanupRef.current = cleanupPromise;
      return cleanupPromise;
    };
    return () => {
      void cleanup();
    };
  }, [
    applyTurnHostEvent,
    commitConfiguredReady,
    commitStatus,
    enqueueOperation,
    isCurrentOperation,
    refreshState,
    revokeTurnGate,
    projection,
    runtime,
    startRuntime,
    updateBoot,
    updateRecovery,
    updateRuntimeState,
  ]);

  return {
    state: {
      boot,
      turnAdmissionReady,
      runtimeState,
      recovery,
      lastEvent,
      lastThreadMetadataEvent,
    },
    lifecycle: {
      startRuntime,
      generalWorkspace,
      stop,
      queryRuntime,
      readRuntimeStorage,
      acknowledgeRecovery,
    },
    turns,
  };
}
