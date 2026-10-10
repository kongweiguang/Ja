// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  AcceptanceEvidence,
  PlanDraft,
  PlanReadModel,
  PlanRevision,
} from "../domain/planModel";
import type { PlanMutationAction, PlanPort } from "./ports";
import { createOperationKey } from "@/shared/operationKey";

export interface PlanController {
  readonly planModel: PlanReadModel | undefined;
  readonly revisions: readonly PlanRevision[];
  readonly evidence: readonly AcceptanceEvidence[];
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly busyAction: PlanMutationAction | undefined;
  refresh(): Promise<void>;
  createPlan(
    ownerThreadId: string,
    objective: string,
    expectedThreadRevision: number,
  ): Promise<boolean>;
  saveDraft(draft: PlanDraft): Promise<boolean>;
  discardDraft(): Promise<boolean>;
  propose(): Promise<boolean>;
  finalizePlan(): Promise<boolean>;
  execute(): Promise<boolean>;
  pausePlan(): Promise<boolean>;
  resumePlan(): Promise<boolean>;
  stopPlan(): Promise<boolean>;
  reject(): Promise<boolean>;
}

interface UsePlanControllerOptions {
  readonly ownerThreadId?: string;
  readonly visible: boolean;
  readonly detailsVisible?: boolean;
  readonly port: PlanPort;
}

/** 计划 controller 保存同一意图的身份供失败重试，成功 ACK 后才释放。 */
function newOperationKey(): string {
  return createOperationKey();
}

/** 展示稳定的恢复语义，诊断细节留在服务端审计。 */
function actionErrorMessage(action: PlanMutationAction | "read"): string {
  switch (action) {
    case "read":
      return "计划暂时不可用，请重试。";
    case "execute":
      return "计划未能启动，请确认当前版本后重试。";
    case "save_draft":
      return "草稿保存失败，本地修改仍保留，请重试。";
    default:
      return "操作未完成，请重试。";
  }
}

/**
 * Plan revision 与事件序号一起构成服务端投影身份；迟到查询不能覆盖更新的 ACK。
 * Thread 切换时 epoch 同时隔离事件、观察和 mutation 的异步结果。
 */
export function usePlanController({
  ownerThreadId,
  visible,
  detailsVisible = false,
  port,
}: UsePlanControllerOptions): PlanController {
  const [planModel, setPlanModel] = useState<PlanReadModel>();
  const [revisions, setRevisions] = useState<readonly PlanRevision[]>([]);
  const [evidence, setEvidence] = useState<readonly AcceptanceEvidence[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [busyAction, setBusyAction] = useState<PlanMutationAction>();
  const modelRef = useRef<PlanReadModel | undefined>(undefined);
  const epochRef = useRef(0);
  const refreshSequenceRef = useRef(0);
  const mutationSequenceRef = useRef(0);
  const mutationInFlightRef = useRef(false);
  const retainedKeysRef = useRef(new Map<PlanMutationAction, { signature: string; key: string }>());

  /** 接纳更高版本或同版本缺失正文的完整回读，拒绝旧投影倒灌。 */
  const acceptPlanModel = useCallback(
    (next: PlanReadModel): boolean => {
      if (ownerThreadId !== undefined && next.plan.ownerThreadId !== ownerThreadId) return false;
      const current = modelRef.current;
      if (current !== undefined && current.plan.planId === next.plan.planId) {
        if (
          current.plan.revision > next.plan.revision ||
          current.eventSequence > next.eventSequence
        )
          return false;
        if (
          current.plan.revision === next.plan.revision &&
          current.eventSequence === next.eventSequence
        ) {
          if (!current.revisionHydrationRequired || next.revisionHydrationRequired) return true;
        }
      }
      modelRef.current = next;
      setPlanModel(next);
      return true;
    },
    [ownerThreadId],
  );

  /** 详情只在可见时读取版本与证据；每次查询都绑定当前 Plan 和 Run 身份。 */
  const refresh = useCallback(async (): Promise<void> => {
    const current = modelRef.current;
    if (!visible || current === undefined) return;
    const epoch = epochRef.current;
    const sequence = ++refreshSequenceRef.current;
    setLoading(true);
    setError(undefined);
    try {
      const next = await port.readPlan({
        ownerThreadId: current.plan.ownerThreadId,
        planId: current.plan.planId,
      });
      if (epoch !== epochRef.current || sequence !== refreshSequenceRef.current) return;
      if (!acceptPlanModel(next)) return;
      if (!detailsVisible) {
        setRevisions([]);
        setEvidence([]);
        return;
      }
      const target =
        next.revision !== null && next.plan.activeRunId !== null
          ? {
              ownerThreadId: next.plan.ownerThreadId,
              planId: next.plan.planId,
              planRevisionId: next.revision.planRevisionId,
              runId: next.plan.activeRunId,
            }
          : undefined;
      const [revisionResult, evidenceResult] = await Promise.all([
        port.planRevisions({ ownerThreadId: next.plan.ownerThreadId, planId: next.plan.planId }),
        target === undefined
          ? Promise.resolve({ items: [] as AcceptanceEvidence[] })
          : port.readPlanEvidence(target),
      ]);
      if (epoch !== epochRef.current || sequence !== refreshSequenceRef.current) return;
      setRevisions(revisionResult.items);
      setEvidence(
        evidenceResult.items.filter((item) => item.planRevisionId === target?.planRevisionId),
      );
    } catch {
      if (epoch === epochRef.current && sequence === refreshSequenceRef.current)
        setError(actionErrorMessage("read"));
    } finally {
      if (epoch === epochRef.current && sequence === refreshSequenceRef.current) setLoading(false);
    }
  }, [acceptPlanModel, detailsVisible, port, visible]);

  /** 当前 Thread 的唯一 Plan 在重启后由服务端恢复；隐藏会话不读取。 */
  useEffect(() => {
    epochRef.current += 1;
    refreshSequenceRef.current += 1;
    mutationSequenceRef.current += 1;
    mutationInFlightRef.current = false;
    retainedKeysRef.current.clear();
    modelRef.current = undefined;
    setPlanModel(undefined);
    setRevisions([]);
    setEvidence([]);
    setError(undefined);
    setBusyAction(undefined);
    if (!visible || ownerThreadId === undefined) return;
    const epoch = epochRef.current;
    let disposed = false;
    void port
      .currentPlan(ownerThreadId)
      .then((current) => {
        if (disposed || epoch !== epochRef.current || current === undefined) return;
        acceptPlanModel(current);
      })
      .catch(() => {
        if (!disposed && epoch === epochRef.current) setError(actionErrorMessage("read"));
      });
    return () => {
      disposed = true;
      epochRef.current += 1;
    };
  }, [acceptPlanModel, ownerThreadId, port, visible]);

  /** Plan 创建和恢复共用观察生命周期；迟到 ACK 立即释放句柄。 */
  useEffect(() => {
    const current = modelRef.current;
    if (!visible || current === undefined || current.plan.ownerThreadId !== ownerThreadId) return;
    let disposed = false;
    let observationId: string | undefined;
    void port
      .observePlan({ ownerThreadId: current.plan.ownerThreadId, planId: current.plan.planId })
      .then((observed) => {
        if (disposed) {
          void port.unobservePlan({ observationId: observed.observationId }).catch(() => undefined);
          return;
        }
        observationId = observed.observationId;
        acceptPlanModel(observed.plan);
      })
      .catch(() => undefined);
    return () => {
      disposed = true;
      if (observationId !== undefined)
        void port.unobservePlan({ observationId }).catch(() => undefined);
    };
  }, [acceptPlanModel, ownerThreadId, planModel?.plan.planId, port, visible]);

  /** 展开详情不重建观察句柄，只加载当前 Plan 的正文。 */
  useEffect(() => {
    if (detailsVisible && modelRef.current !== undefined) void refresh();
  }, [detailsVisible, refresh]);

  /** 事件只提供轻量摘要，版本变化时补读正文；观察端不猜测执行结果。 */
  useEffect(
    () =>
      port.subscribe((event) => {
        if (!visible || event.ownerThreadId !== ownerThreadId) return;
        const current = modelRef.current;
        if (current === undefined || current.plan.planId !== event.planId) return;
        if (event.eventSequence <= current.eventSequence) return;
        const revisionChanged =
          event.plan.activePlanRevisionId !== current.plan.activePlanRevisionId ||
          (event.plan.revision !== current.plan.revision &&
            (event.plan.status === "draft" || event.plan.status === "awaiting_approval"));
        const accepted = acceptPlanModel({
          ...current,
          plan: event.plan,
          revision: revisionChanged ? null : current.revision,
          draft: revisionChanged ? null : current.draft,
          revisionHydrationRequired: revisionChanged || current.revisionHydrationRequired,
          progress: event.progress,
          eventSequence: event.eventSequence,
        });
        if (accepted && revisionChanged) void refresh();
      }),
    [acceptPlanModel, ownerThreadId, port, refresh, visible],
  );

  /** 一次创建意图复用身份，回执丢失时不会生成重复 Plan。 */
  const createPlan = useCallback(
    async (
      requestedOwnerThreadId: string,
      objective: string,
      expectedThreadRevision: number,
    ): Promise<boolean> => {
      if (
        !visible ||
        mutationInFlightRef.current ||
        expectedThreadRevision < 0 ||
        requestedOwnerThreadId !== ownerThreadId
      )
        return false;
      const existing = modelRef.current?.plan;
      if (existing !== undefined && !["completed", "stopped"].includes(existing.status))
        return true;
      const normalizedObjective = objective.trim();
      if (normalizedObjective === "") return false;
      const epoch = epochRef.current;
      const sequence = ++mutationSequenceRef.current;
      const signature = `${requestedOwnerThreadId}:${expectedThreadRevision}:${normalizedObjective}`;
      const retained = retainedKeysRef.current.get("create_plan");
      const key = retained?.signature === signature ? retained.key : newOperationKey();
      retainedKeysRef.current.set("create_plan", { signature, key });
      mutationInFlightRef.current = true;
      setBusyAction("create_plan");
      setError(undefined);
      try {
        const next = await port.createPlan({
          ownerThreadId: requestedOwnerThreadId,
          objective: normalizedObjective,
          expectedThreadRevision,
          operationKey: key,
        });
        if (epoch !== epochRef.current || sequence !== mutationSequenceRef.current) return false;
        if (!acceptPlanModel(next)) return false;
        retainedKeysRef.current.delete("create_plan");
        return true;
      } catch {
        if (epoch === epochRef.current && sequence === mutationSequenceRef.current)
          setError(actionErrorMessage("create_plan"));
        return false;
      } finally {
        if (sequence === mutationSequenceRef.current) {
          mutationInFlightRef.current = false;
          setBusyAction(undefined);
        }
      }
    },
    [acceptPlanModel, ownerThreadId, port, visible],
  );

  /** Mutation ACK 先于后台回读接纳，失败重试沿用同一 key。 */
  const mutate = useCallback(
    async (
      action: PlanMutationAction,
      signature: string,
      operation: (key: string, current: PlanReadModel) => Promise<PlanReadModel>,
    ): Promise<boolean> => {
      const current = modelRef.current;
      if (current === undefined || mutationInFlightRef.current) return false;
      const epoch = epochRef.current;
      const sequence = ++mutationSequenceRef.current;
      const retained = retainedKeysRef.current.get(action);
      const key = retained?.signature === signature ? retained.key : newOperationKey();
      retainedKeysRef.current.set(action, { signature, key });
      mutationInFlightRef.current = true;
      setBusyAction(action);
      setError(undefined);
      try {
        const next = await operation(key, current);
        if (epoch !== epochRef.current || sequence !== mutationSequenceRef.current) return false;
        if (!acceptPlanModel(next)) return false;
        retainedKeysRef.current.delete(action);
        refreshSequenceRef.current += 1;
        if (detailsVisible) void refresh();
        return true;
      } catch {
        if (epoch === epochRef.current && sequence === mutationSequenceRef.current)
          setError(actionErrorMessage(action));
        return false;
      } finally {
        if (sequence === mutationSequenceRef.current) {
          mutationInFlightRef.current = false;
          setBusyAction(undefined);
        }
      }
    },
    [acceptPlanModel, detailsVisible, refresh],
  );

  return useMemo(
    () => ({
      planModel,
      revisions,
      evidence,
      loading,
      error,
      busyAction,
      refresh,
      createPlan,
      saveDraft: (draft: PlanDraft) =>
        mutate("save_draft", JSON.stringify(draft), (key, current) =>
          port.saveDraft({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            expectedPlanRevision: current.plan.revision,
            operationKey: key,
            draft,
          }),
        ),
      discardDraft: () =>
        mutate("discard_draft", "discard", (key, current) =>
          port.discardDraft({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            expectedPlanRevision: current.plan.revision,
            operationKey: key,
          }),
        ),
      propose: () =>
        mutate("propose", "propose", (key, current) =>
          port.propose({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            expectedPlanRevision: current.plan.revision,
            operationKey: key,
          }),
        ),
      finalizePlan: () =>
        mutate("propose", "propose", (key, current) =>
          port.propose({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            expectedPlanRevision: current.plan.revision,
            operationKey: key,
          }),
        ),
      execute: () =>
        mutate("execute", planModel?.revision?.planRevisionId ?? "missing", (key, current) => {
          if (current.revision === null) return Promise.reject(new Error("plan revision missing"));
          return port.execute({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            planRevisionId: current.revision.planRevisionId,
            planHash: current.revision.planHash,
            expectedPlanRevision: current.plan.revision,
            operationKey: key,
          });
        }),
      pausePlan: () =>
        mutate("pause", planModel?.plan.activeRunId ?? "missing", (key, current) => {
          if (current.plan.activeRunId === null)
            return Promise.reject(new Error("plan run missing"));
          return port.pausePlan({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            runId: current.plan.activeRunId,
            expectedPlanRevision: current.plan.revision,
            operationKey: key,
          });
        }),
      resumePlan: () =>
        mutate("resume", planModel?.plan.activeRunId ?? "missing", (key, current) => {
          if (current.plan.activeRunId === null)
            return Promise.reject(new Error("plan run missing"));
          return port.resumePlan({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            runId: current.plan.activeRunId,
            expectedPlanRevision: current.plan.revision,
            operationKey: key,
          });
        }),
      stopPlan: () =>
        mutate("stop", planModel?.plan.activeRunId ?? "missing", (key, current) => {
          if (current.plan.activeRunId === null)
            return Promise.reject(new Error("plan run missing"));
          return port.stopPlan({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            runId: current.plan.activeRunId,
            expectedPlanRevision: current.plan.revision,
            operationKey: key,
          });
        }),
      reject: () =>
        mutate("reject", planModel?.revision?.planRevisionId ?? "missing", (key, current) =>
          port.reject({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            expectedPlanRevision: current.plan.revision,
            operationKey: key,
          }),
        ),
    }),
    [busyAction, createPlan, error, evidence, loading, mutate, planModel, port, refresh, revisions],
  );
}
