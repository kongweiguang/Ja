// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useRef, type MutableRefObject } from "react";
import {
  RuntimeApplicationError as RuntimeHostError,
  normalizeRuntimeApplicationError as normalizeRuntimeError,
  type ApprovalResponseInput,
  type RuntimeHostPort,
  type RuntimeHostEvent,
  type RuntimeProjectionPort,
  type RuntimeRecoveryState,
  type RuntimeStatus,
  type TurnAccepted,
  type TurnCancelInput,
  type TurnCancelResult,
  type TurnQueuedInput,
  type TurnQueuedInputResult,
  type TurnStartInput,
} from "./runtimePorts";
import type { BootState } from "../bootState";

export interface RuntimePendingOperation<T> {
  readonly epoch: number;
  readonly promise: Promise<T>;
}

export interface RuntimeTurnController {
  readonly submitTurn: (input: TurnStartInput) => Promise<TurnAccepted>;
  readonly cancelTurn: (input: TurnCancelInput) => Promise<TurnCancelResult>;
  readonly steerTurn: (input: TurnQueuedInput) => Promise<TurnQueuedInputResult>;
  readonly followUpTurn: (input: TurnQueuedInput) => Promise<TurnQueuedInputResult>;
  readonly approvalRespond: (input: ApprovalResponseInput) => Promise<void>;
  readonly applyHostEvent: (event: Exclude<RuntimeHostEvent, { kind: "status" }>) => boolean;
}

interface PendingTurnEventBuffer {
  readonly events: Extract<RuntimeHostEvent, { kind: "timeline" }>[];
  readonly turnIds: Set<string>;
  readonly submittedAt: string;
  readonly submittedText: string;
}

interface RuntimeTurnControllerOptions {
  readonly runtime: RuntimeHostPort;
  readonly projection: RuntimeProjectionPort;
  readonly lifecycleEpochRef: MutableRefObject<number>;
  readonly runtimeStateRef: MutableRefObject<RuntimeStatus | undefined>;
  readonly recoveryRef: MutableRefObject<RuntimeRecoveryState | undefined>;
  readonly bootRef: MutableRefObject<BootState>;
  readonly enqueueOperation: <T>(
    key: string,
    operation: () => Promise<T>,
  ) => RuntimePendingOperation<T>;
  readonly isTurnGateCurrent: (lifecycleEpoch: number, generation: number) => boolean;
  readonly isTurnGenerationCurrent: (lifecycleEpoch: number, generation: number) => boolean;
}

/** typed turn/start 的可见文本只用于关联早到事件，不保存 tool 或隐私 payload。 */
function submittedTurnText(input: TurnStartInput): string {
  return input.content
    .filter(
      (part): part is Extract<(typeof input.content)[number], { type: "text" }> =>
        part.type === "text",
    )
    .map((part) => part.text)
    .join("\n");
}

/** 早到 Timeline event 优先按 Thread，缺失时按已知 Turn identity 关联唯一 pending buffer。 */
function pendingBufferForEvent(
  buffers: Map<string, PendingTurnEventBuffer>,
  event: Extract<RuntimeHostEvent, { kind: "timeline" }>,
): PendingTurnEventBuffer | undefined {
  const params = event.event.params as { threadId?: unknown; turnId?: unknown };
  if (typeof params.threadId === "string") return buffers.get(params.threadId);
  if (typeof params.turnId !== "string") return undefined;
  return Array.from(buffers.values()).find((buffer) => buffer.turnIds.has(params.turnId as string));
}

/**
 * Turn controller 独占 early-event buffer 与 Turn/Approval 用例；它只读取 lifecycle controller
 * 提供的 generation gate、串行 operation port 和注入的 projection port，不知道 Conversation
 * Store，也不拥有 sidecar 启停或恢复状态。
 */
export function useRuntimeTurnController({
  runtime,
  projection,
  lifecycleEpochRef,
  runtimeStateRef,
  recoveryRef,
  bootRef,
  enqueueOperation,
  isTurnGateCurrent,
  isTurnGenerationCurrent,
}: RuntimeTurnControllerOptions): RuntimeTurnController {
  const pendingEventsRef = useRef<Map<string, PendingTurnEventBuffer>>(new Map());

  /**
   * start 请求在串行 lane 入口前后都复核 generation；ACK 前缓存早到事件，ACK 后先投影
   * accepted identity 再按原顺序重放，避免 Timeline 出现无归属增量。
   */
  const submitTurn = useCallback(
    (input: TurnStartInput): Promise<TurnAccepted> => {
      const lifecycleEpoch = lifecycleEpochRef.current;
      const generation = runtimeStateRef.current?.generation;
      if (generation === undefined || !isTurnGateCurrent(lifecycleEpoch, generation)) {
        return Promise.reject(
          new RuntimeHostError("RUNTIME_NOT_READY", "运行时尚未完成配置", true),
        );
      }
      const key = `turnStart:${input.threadId}`;
      if (!pendingEventsRef.current.has(input.threadId)) {
        pendingEventsRef.current.set(input.threadId, {
          events: [],
          turnIds: new Set(),
          submittedAt: new Date().toISOString(),
          submittedText: submittedTurnText(input),
        });
      }
      const pending = enqueueOperation(key, () => {
        if (!isTurnGateCurrent(lifecycleEpoch, generation)) {
          throw new RuntimeHostError("RUNTIME_NOT_READY", "运行时状态已变化，请重试", true);
        }
        return runtime.turnStart(input);
      });
      return pending.promise
        .then((accepted) => {
          if (!isTurnGenerationCurrent(lifecycleEpoch, generation)) {
            throw new RuntimeHostError("RUNTIME_NOT_READY", "运行时状态已变化，请重试", true);
          }
          const buffer = pendingEventsRef.current.get(input.threadId);
          if (buffer !== undefined) {
            buffer.turnIds.add(accepted.turnId);
            projection.applyTurnAccepted({
              threadId: input.threadId,
              turnId: accepted.turnId,
              threadRevision: accepted.threadRevision,
              submittedText: buffer.submittedText,
              submittedAt: buffer.submittedAt,
            });
            pendingEventsRef.current.delete(input.threadId);
            for (const event of buffer.events) projection.applyHostEvent(event);
          }
          return accepted;
        })
        .catch((error: unknown) => {
          const buffer = pendingEventsRef.current.get(input.threadId);
          pendingEventsRef.current.delete(input.threadId);
          if (buffer !== undefined) {
            for (const event of buffer.events) projection.applyHostEvent(event);
          }
          throw normalizeRuntimeError(error);
        });
    },
    [
      enqueueOperation,
      isTurnGateCurrent,
      isTurnGenerationCurrent,
      lifecycleEpochRef,
      projection,
      runtime,
      runtimeStateRef,
    ],
  );

  /**
   * cancel response 不是终态事实；命令只受 recovery 与 generation fence 约束，权威完成状态
   * 仍由 Java event stream 发布，避免响应和事件形成双 owner。
   */
  const cancelTurn = useCallback(
    (input: TurnCancelInput): Promise<TurnCancelResult> => {
      const lifecycleEpoch = lifecycleEpochRef.current;
      if (
        recoveryRef.current?.required === true ||
        bootRef.current.status === "recovery_required"
      ) {
        return Promise.reject(
          new RuntimeHostError("RECOVERY_REQUIRED", "需要先完成运行时恢复", false),
        );
      }
      const expectedGeneration = runtimeStateRef.current?.generation;
      const key = `turnCancel:${input.turnId}:${input.expectedThreadRevision}`;
      const pending = enqueueOperation(key, () => {
        const currentGeneration = runtimeStateRef.current?.generation;
        if (
          expectedGeneration !== undefined &&
          currentGeneration !== undefined &&
          currentGeneration !== expectedGeneration
        ) {
          throw new RuntimeHostError("RUNTIME_NOT_READY", "运行时状态已变化，请重试", true);
        }
        return runtime.turnCancel(input);
      });
      return pending.promise
        .then((result) => {
          void lifecycleEpoch;
          return result;
        })
        .catch((error: unknown) => {
          throw normalizeRuntimeError(error);
        });
    },
    [bootRef, enqueueOperation, lifecycleEpochRef, recoveryRef, runtime, runtimeStateRef],
  );

  /** steering/follow-up 共用 generation fence 与串行 lane，durable FIFO 仍由 Java 独占。 */
  const queueTurnInput = useCallback(
    (kind: "steering" | "follow_up", input: TurnQueuedInput): Promise<TurnQueuedInputResult> => {
      const lifecycleEpoch = lifecycleEpochRef.current;
      const expectedGeneration = runtimeStateRef.current?.generation;
      const key = `turnInput:${kind}:${input.turnId}`;
      const pending = enqueueOperation(key, () => {
        if (
          recoveryRef.current?.required === true ||
          bootRef.current.status === "recovery_required"
        ) {
          throw new RuntimeHostError("RECOVERY_REQUIRED", "需要先完成运行时恢复", false);
        }
        const currentGeneration = runtimeStateRef.current?.generation;
        if (expectedGeneration === undefined || currentGeneration !== expectedGeneration) {
          throw new RuntimeHostError("RUNTIME_NOT_READY", "运行时状态已变化，请重试", true);
        }
        return kind === "steering" ? runtime.turnSteer(input) : runtime.turnFollowUp(input);
      });
      return pending.promise
        .then((result) => {
          if (lifecycleEpochRef.current !== lifecycleEpoch) {
            throw new RuntimeHostError("RUNTIME_NOT_READY", "运行时状态已变化，请重试", true);
          }
          return result;
        })
        .catch((error: unknown) => {
          throw normalizeRuntimeError(error);
        });
    },
    [bootRef, enqueueOperation, lifecycleEpochRef, recoveryRef, runtime, runtimeStateRef],
  );

  /** steering 只为下一个 Tool boundary 排队 guidance，不中断当前工作。 */
  const steerTurn = useCallback(
    (input: TurnQueuedInput): Promise<TurnQueuedInputResult> => queueTurnInput("steering", input),
    [queueTurnInput],
  );

  /** follow-up 只在 active Turn 即将结束时消费，保持与 steering 不同的业务语义。 */
  const followUpTurn = useCallback(
    (input: TurnQueuedInput): Promise<TurnQueuedInputResult> => queueTurnInput("follow_up", input),
    [queueTurnInput],
  );

  /** Approval decision 按业务 identity 合并 in-flight，不向 Context 暴露 private request id。 */
  const approvalRespond = useCallback(
    (input: ApprovalResponseInput): Promise<void> => {
      const pending = enqueueOperation(`approvalRespond:${input.approvalId}`, () =>
        runtime.approvalRespond(input),
      );
      return pending.promise
        .then(() => undefined)
        .catch((error: unknown) => {
          throw normalizeRuntimeError(error);
        });
    },
    [enqueueOperation, runtime],
  );

  /**
   * lifecycle subscription 把非 status event 交给 Turn controller；start ACK 前先缓存，
   * 其它事件直接进入 Timeline，返回值只表示是否需要 lifecycle 刷新 authoritative state。
   */
  const applyHostEvent = useCallback(
    (event: Exclude<RuntimeHostEvent, { kind: "status" }>): boolean => {
      if (event.kind === "timeline") {
        const buffer = pendingBufferForEvent(pendingEventsRef.current, event);
        if (buffer !== undefined) {
          const turnId = (event.event.params as { turnId?: unknown }).turnId;
          if (typeof turnId === "string") buffer.turnIds.add(turnId);
          buffer.events.push(event);
          return false;
        }
      }
      projection.applyHostEvent(event);
      return event.kind === "timeline" && event.event.method === "turn/terminal";
    },
    [projection],
  );

  return { submitTurn, cancelTurn, steerTurn, followUpTurn, approvalRespond, applyHostEvent };
}
