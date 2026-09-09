// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  AcceptanceEvidence,
  GoalReadModel,
  PlanDraft,
  PlanReadModel,
  PlanRevision,
} from "../domain/goalModel";
import type { GoalMutationAction, GoalPort, GoalPortError } from "./ports";

export interface GoalController {
  readonly model: GoalReadModel | undefined;
  readonly planModel: PlanReadModel | undefined;
  readonly revisions: readonly PlanRevision[];
  readonly evidence: readonly AcceptanceEvidence[];
  readonly loading: boolean;
  readonly error: string | undefined;
  readonly busyAction: GoalMutationAction | undefined;
  refresh(): Promise<void>;
  create(ownerThreadId: string, objective: string): Promise<boolean>;
  createPlan(
    ownerThreadId: string,
    objective: string,
    expectedThreadRevision: number,
  ): Promise<boolean>;
  pause(): Promise<boolean>;
  resume(): Promise<boolean>;
  stop(): Promise<boolean>;
  respondInput(response: string): Promise<boolean>;
  saveDraft(draft: PlanDraft): Promise<boolean>;
  discardDraft(): Promise<boolean>;
  propose(): Promise<boolean>;
  approve(): Promise<boolean>;
  execute(): Promise<boolean>;
  attachPlan(): Promise<boolean>;
  detachPlan(): Promise<boolean>;
  reject(): Promise<boolean>;
}

interface UseGoalControllerOptions {
  readonly goalId?: string;
  readonly ownerThreadId?: string;
  readonly visible: boolean;
  readonly detailsVisible?: boolean;
  readonly port: GoalPort;
}

/** 幂等键仅标识一次明确 UI 意图；失败重试复用，成功后由 controller 释放。 */
function newIdempotencyKey(action: GoalMutationAction): string {
  const random =
    globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `goal-ui-${action}-${random}`;
}

/** UI 只显示稳定恢复语义，底层诊断由 adapter/App Server 日志持有。 */
function actionErrorMessage(action: GoalMutationAction | "read"): string {
  switch (action) {
    case "read":
      return "目标暂时不可用，请重试。";
    case "approve":
      return "计划版本已变化，请刷新后重新批准。";
    case "execute":
      return "计划未能启动，请确认批准版本后重试。";
    case "attach_plan":
      return "计划未能用于当前目标，请确认计划仍已批准。";
    case "respond_input":
      return "输入未提交，请检查目标状态后重试。";
    case "save_draft":
      return "计划草稿未保存，请重试。";
    default:
      return "操作未完成，请重试。";
  }
}

/**
 * 生产 adapter 始终携带 event sequence；纯展示模型缺少该元数据时回退到 goal revision，
 * 仍保持单调而不为测试或静态展示制造第二套状态来源。
 */
function modelEventSequence(model: GoalReadModel): number {
  return model.eventSequence ?? model.goal.revision;
}

/** linked Plan 从同一 Goal read 中恢复成独立投影，便于 Plan CAS 与 Goal CAS 分 lane。 */
function linkedPlanModel(model: GoalReadModel): PlanReadModel | undefined {
  if (model.planState === null) return undefined;
  return {
    plan: model.planState,
    revision: model.plan,
    draft: model.draft,
    approvedPlanRevisionId:
      model.plan?.approvedAt === null ? null : (model.plan?.planRevisionId ?? null),
    eventSequence: model.planEventSequence ?? model.planState.revision,
  };
}

/** 只识别 adapter 暴露的稳定错误码，避免依赖跨 WebView 边界不可靠的 instanceof。 */
function isGoalPortErrorCode(error: unknown, code: GoalPortError["code"]): boolean {
  return (
    error !== null &&
    typeof error === "object" &&
    "code" in error &&
    (error as { readonly code?: unknown }).code === code
  );
}

/**
 * pause 已落库但旧 Turn/lease 尚未完全收口时，服务端会短暂返回 GOAL_INVALID_STATE。
 * 重试次数有界且复用同一幂等键，既等待安全边界，也不扩大成无限后台轮询。
 */
async function retrySettlingGoalMutation(
  operation: () => Promise<GoalReadModel>,
): Promise<GoalReadModel> {
  const maximumAttempts = 20;
  for (let attempt = 1; attempt <= maximumAttempts; attempt += 1) {
    try {
      return await operation();
    } catch (error) {
      if (!isGoalPortErrorCode(error, "GOAL_INVALID_STATE") || attempt === maximumAttempts)
        throw error;
      await new Promise<void>((resolve) => {
        globalThis.setTimeout(resolve, 50);
      });
    }
  }
  throw new Error("unreachable Goal settlement retry state");
}

/**
 * Controller 以服务端 revision 为唯一真相：先读快照再 observe，事件只触发重读；任何 mutation
 * 只有收到 ACK 才替换投影，避免 React 猜测 Goal 状态或计划 hash。
 */
export function useGoalController({
  goalId,
  ownerThreadId,
  visible,
  detailsVisible = false,
  port,
}: UseGoalControllerOptions): GoalController {
  const [provisionalGoal, setProvisionalGoal] = useState<{
    readonly goalId: string;
    readonly ownerThreadId: string;
  }>();
  const [model, setModel] = useState<GoalReadModel>();
  const [planModel, setPlanModel] = useState<PlanReadModel>();
  const [revisions, setRevisions] = useState<readonly PlanRevision[]>([]);
  const [evidence, setEvidence] = useState<readonly AcceptanceEvidence[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [busyAction, setBusyAction] = useState<GoalMutationAction>();
  const epochRef = useRef(0);
  const refreshSequenceRef = useRef(0);
  const mutationSequenceRef = useRef(0);
  const mutationInFlightRef = useRef(false);
  const modelRef = useRef<GoalReadModel | undefined>(undefined);
  const planModelRef = useRef<PlanReadModel | undefined>(undefined);
  const refreshRef = useRef<() => Promise<void>>(async () => undefined);
  const retainedKeysRef = useRef(
    new Map<GoalMutationAction, { readonly signature: string; readonly key: string }>(),
  );
  const effectiveGoalId =
    goalId ??
    (provisionalGoal !== undefined && provisionalGoal.ownerThreadId === ownerThreadId
      ? provisionalGoal.goalId
      : undefined);

  /**
   * revision 与 event sequence 共同构成服务端投影 identity；任一维度回退都拒绝，完全相同的
   * identity 也不覆盖现有对象，避免缓存或迟到 read 用矛盾正文改写已接纳的 ACK。
   */
  const acceptModel = useCallback(
    (next: GoalReadModel): boolean => {
      if (next.goal.goalId !== effectiveGoalId) return false;
      const current = modelRef.current;
      const nextEventSequence = modelEventSequence(next);
      if (
        current !== undefined &&
        (current.goal.revision > next.goal.revision ||
          modelEventSequence(current) > nextEventSequence)
      )
        return false;
      if (
        current !== undefined &&
        current.goal.revision === next.goal.revision &&
        modelEventSequence(current) === nextEventSequence
      )
        return true;
      modelRef.current = next;
      setModel(next);
      const linked = linkedPlanModel(next);
      if (linked !== undefined) {
        planModelRef.current = linked;
        setPlanModel(linked);
      }
      return true;
    },
    [effectiveGoalId],
  );

  /** Plan revision/event identity 独立单调；批准不会借 Goal revision 伪造进展。 */
  const acceptPlanModel = useCallback((next: PlanReadModel): boolean => {
    const current = planModelRef.current;
    if (current !== undefined && current.plan.planId !== next.plan.planId) {
      planModelRef.current = next;
      setPlanModel(next);
      return true;
    }
    if (
      current !== undefined &&
      (current.plan.revision > next.plan.revision || current.eventSequence > next.eventSequence)
    )
      return false;
    if (
      current !== undefined &&
      current.plan.revision === next.plan.revision &&
      current.eventSequence === next.eventSequence
    )
      return true;
    planModelRef.current = next;
    setPlanModel(next);
    return true;
  }, []);

  /**
   * Goal 与 standalone Plan 分别回读各自 CAS 投影；linked Plan 已由 Goal read 精确补齐。epoch 和
   * refresh sequence 淘汰迟到请求，辅助详情只挂接最终接纳的 Plan identity。
   */
  const refresh = useCallback(async (): Promise<void> => {
    if (!visible || (effectiveGoalId === undefined && planModelRef.current === undefined)) return;
    const epoch = epochRef.current;
    const refreshSequence = ++refreshSequenceRef.current;
    setLoading(true);
    setError(undefined);
    try {
      const nextGoal =
        effectiveGoalId === undefined ? undefined : await port.read({ goalId: effectiveGoalId });
      if (epoch !== epochRef.current || refreshSequence !== refreshSequenceRef.current) return;
      if (nextGoal !== undefined && !acceptModel(nextGoal)) return;
      if (!detailsVisible) {
        setRevisions([]);
        setEvidence([]);
        return;
      }
      const embeddedLinkedPlan = nextGoal === undefined ? undefined : linkedPlanModel(nextGoal);
      let selectedPlan = embeddedLinkedPlan ?? planModelRef.current;
      if (embeddedLinkedPlan !== undefined) {
        if (!acceptPlanModel(embeddedLinkedPlan)) return;
        selectedPlan = embeddedLinkedPlan;
      } else if (nextGoal !== undefined && nextGoal.goal.activePlanId !== null) {
        const linkedPlan = await port.readPlan({
          ownerThreadId: nextGoal.goal.ownerThreadId,
          planId: nextGoal.goal.activePlanId,
        });
        if (epoch !== epochRef.current || refreshSequence !== refreshSequenceRef.current) return;
        if (!acceptPlanModel(linkedPlan)) return;
        selectedPlan = linkedPlan;
      } else if (selectedPlan !== undefined) {
        const nextPlan = await port.readPlan({
          ownerThreadId: selectedPlan.plan.ownerThreadId,
          planId: selectedPlan.plan.planId,
        });
        if (epoch !== epochRef.current || refreshSequence !== refreshSequenceRef.current) return;
        if (!acceptPlanModel(nextPlan)) return;
        selectedPlan = nextPlan;
      }
      const evidencePlanRevisionId =
        nextGoal === undefined
          ? undefined
          : nextGoal.goal.activePlanId === null
            ? null
            : selectedPlan?.revision !== null &&
                selectedPlan?.revision !== undefined &&
                selectedPlan.plan.planId === nextGoal.goal.activePlanId &&
                selectedPlan.revision.planRevisionId === nextGoal.goal.activePlanRevisionId
              ? selectedPlan.revision.planRevisionId
              : undefined;
      const [revisionResult, evidenceResult] = await Promise.all([
        selectedPlan === undefined
          ? Promise.resolve({ items: [] as PlanRevision[] })
          : port.planRevisions({
              ownerThreadId: selectedPlan.plan.ownerThreadId,
              planId: selectedPlan.plan.planId,
            }),
        nextGoal === undefined || evidencePlanRevisionId === undefined
          ? Promise.resolve({ items: [] as AcceptanceEvidence[] })
          : port.evidence({
              goalId: nextGoal.goal.goalId,
              goalDefinitionRevision: nextGoal.goal.goalDefinitionRevision,
              planRevisionId: evidencePlanRevisionId,
            }),
      ]);
      if (epoch !== epochRef.current || refreshSequence !== refreshSequenceRef.current) return;
      setRevisions(revisionResult.items);
      setEvidence(
        nextGoal === undefined || evidencePlanRevisionId === undefined
          ? []
          : evidenceResult.items.filter(
              (item) =>
                item.goalDefinitionRevision === nextGoal.goal.goalDefinitionRevision &&
                item.planRevisionId === evidencePlanRevisionId,
            ),
      );
    } catch {
      if (epoch === epochRef.current && refreshSequence === refreshSequenceRef.current)
        setError(actionErrorMessage("read"));
    } finally {
      if (epoch === epochRef.current && refreshSequence === refreshSequenceRef.current)
        setLoading(false);
    }
  }, [acceptModel, acceptPlanModel, detailsVisible, effectiveGoalId, port, visible]);

  /**
   * 详情显隐只替换最新 refresh 实现并按需读取重型投影，不能进入 Goal observation 的依赖。
   * 否则展开 Plan sheet 会制造 unobserve 间隙，恰在此时启动的 continuation 将永久失去 Turn 流。
   */
  useEffect(() => {
    refreshRef.current = refresh;
    if (modelRef.current !== undefined) void refresh();
  }, [refresh]);

  /**
   * `/goal` 显式创建才建立 Goal，并把 ACK identity 暂存在当前 owner Thread；该 identity 只用于
   * 立刻读取服务端聚合，Thread catalog 回读后仍由 activeGoalId 接管，不生成第二份 Goal 状态。
   */
  const create = useCallback(
    async (requestedOwnerThreadId: string, objective: string): Promise<boolean> => {
      if (
        !visible ||
        effectiveGoalId !== undefined ||
        mutationInFlightRef.current ||
        (ownerThreadId !== undefined && requestedOwnerThreadId !== ownerThreadId)
      )
        return effectiveGoalId !== undefined;
      const normalizedObjective = objective.trim();
      if (normalizedObjective === "") return false;
      const epoch = epochRef.current;
      const mutationSequence = ++mutationSequenceRef.current;
      const signature = `${requestedOwnerThreadId}:${normalizedObjective}`;
      const retained = retainedKeysRef.current.get("create");
      const key = retained?.signature === signature ? retained.key : newIdempotencyKey("create");
      retainedKeysRef.current.set("create", { signature, key });
      mutationInFlightRef.current = true;
      setBusyAction("create");
      setError(undefined);
      try {
        const next = await port.create({
          ownerThreadId: requestedOwnerThreadId,
          objective: normalizedObjective,
          expectedGoalRevision: 0,
          idempotencyKey: key,
        });
        if (epoch !== epochRef.current || mutationSequence !== mutationSequenceRef.current)
          return false;
        retainedKeysRef.current.delete("create");
        setProvisionalGoal({
          goalId: next.goal.goalId,
          ownerThreadId: requestedOwnerThreadId,
        });
        modelRef.current = next;
        setModel(next);
        return true;
      } catch {
        if (epoch === epochRef.current && mutationSequence === mutationSequenceRef.current)
          setError(actionErrorMessage("create"));
        return false;
      } finally {
        if (mutationSequence === mutationSequenceRef.current) {
          mutationInFlightRef.current = false;
          setBusyAction(undefined);
        }
      }
    },
    [effectiveGoalId, ownerThreadId, port, visible],
  );

  /**
   * Plan mode 创建独立 Plan artifact，不预建 Goal 或把 Plan 自动挂接到当前 Goal；Thread CAS
   * 由发起发送时观察到的 snapshot revision 提供，失败重试复用同一幂等 identity。
   */
  const createPlan = useCallback(
    async (
      requestedOwnerThreadId: string,
      objective: string,
      expectedThreadRevision: number,
    ): Promise<boolean> => {
      if (!visible || mutationInFlightRef.current || expectedThreadRevision < 0) return false;
      const normalizedObjective = objective.trim();
      if (normalizedObjective === "") return false;
      const epoch = epochRef.current;
      const mutationSequence = ++mutationSequenceRef.current;
      const signature = `${requestedOwnerThreadId}:${expectedThreadRevision}:${normalizedObjective}`;
      const retained = retainedKeysRef.current.get("create_plan");
      const key =
        retained?.signature === signature ? retained.key : newIdempotencyKey("create_plan");
      retainedKeysRef.current.set("create_plan", { signature, key });
      mutationInFlightRef.current = true;
      setBusyAction("create_plan");
      setError(undefined);
      try {
        const next = await port.createPlan({
          ownerThreadId: requestedOwnerThreadId,
          objective: normalizedObjective,
          expectedThreadRevision,
          idempotencyKey: key,
        });
        if (epoch !== epochRef.current || mutationSequence !== mutationSequenceRef.current)
          return false;
        if (!acceptPlanModel(next)) return false;
        retainedKeysRef.current.delete("create_plan");
        return true;
      } catch {
        if (epoch === epochRef.current && mutationSequence === mutationSequenceRef.current)
          setError(actionErrorMessage("create_plan"));
        return false;
      } finally {
        if (mutationSequence === mutationSequenceRef.current) {
          mutationInFlightRef.current = false;
          setBusyAction(undefined);
        }
      }
    },
    [acceptPlanModel, port, visible],
  );

  /**
   * 同一内容的失败重试复用 key；mutation ACK 已包含完整权威投影，接纳前先推进 refresh fence，
   * 使 ACK 前启动的 read 全部失效。辅助详情后台刷新不得让下一阶段主动作可见却不可用。
   */
  const mutate = useCallback(
    async (
      action: GoalMutationAction,
      signature: string,
      operation: (key: string, current: GoalReadModel) => Promise<GoalReadModel>,
    ): Promise<boolean> => {
      const current = modelRef.current;
      if (current === undefined || mutationInFlightRef.current) return false;
      const epoch = epochRef.current;
      const mutationSequence = ++mutationSequenceRef.current;
      const retained = retainedKeysRef.current.get(action);
      const key = retained?.signature === signature ? retained.key : newIdempotencyKey(action);
      retainedKeysRef.current.set(action, { signature, key });
      mutationInFlightRef.current = true;
      setBusyAction(action);
      setError(undefined);
      try {
        const next = await operation(key, current);
        if (epoch !== epochRef.current || mutationSequence !== mutationSequenceRef.current)
          return false;
        retainedKeysRef.current.delete(action);
        refreshSequenceRef.current += 1;
        acceptModel(next);
        void refresh();
        return true;
      } catch {
        if (epoch === epochRef.current && mutationSequence === mutationSequenceRef.current)
          setError(actionErrorMessage(action));
        return false;
      } finally {
        if (mutationSequence === mutationSequenceRef.current) {
          mutationInFlightRef.current = false;
          setBusyAction(undefined);
        }
      }
    },
    [acceptModel, refresh],
  );

  /** Plan mutation 使用独立 revision lane；ACK 只更新 Plan，关联 Goal 必须另走 attach mutation。 */
  const mutatePlan = useCallback(
    async (
      action: GoalMutationAction,
      signature: string,
      operation: (key: string, current: PlanReadModel) => Promise<PlanReadModel>,
    ): Promise<boolean> => {
      const current = planModelRef.current;
      if (current === undefined || mutationInFlightRef.current) return false;
      const epoch = epochRef.current;
      const mutationSequence = ++mutationSequenceRef.current;
      const retained = retainedKeysRef.current.get(action);
      const key = retained?.signature === signature ? retained.key : newIdempotencyKey(action);
      retainedKeysRef.current.set(action, { signature, key });
      mutationInFlightRef.current = true;
      setBusyAction(action);
      setError(undefined);
      try {
        const next = await operation(key, current);
        if (epoch !== epochRef.current || mutationSequence !== mutationSequenceRef.current)
          return false;
        if (!acceptPlanModel(next)) return false;
        retainedKeysRef.current.delete(action);
        void refresh();
        return true;
      } catch {
        if (epoch === epochRef.current && mutationSequence === mutationSequenceRef.current)
          setError(actionErrorMessage(action));
        return false;
      } finally {
        if (mutationSequence === mutationSequenceRef.current) {
          mutationInFlightRef.current = false;
          setBusyAction(undefined);
        }
      }
    },
    [acceptPlanModel, refresh],
  );

  /**
   * 关联变更会替换 Goal run；活动 Goal 必须先持久化暂停并等待旧 Turn/lease 收口。每个子动作
   * 使用根 UI 意图派生的稳定幂等键，且中途 ACK 立即成为界面真相，失败时保留可恢复 paused 状态。
   */
  const replaceGoalPlanBinding = useCallback(
    async (
      key: string,
      current: GoalReadModel,
      operation: (mutationKey: string, settled: GoalReadModel) => Promise<GoalReadModel>,
    ): Promise<GoalReadModel> => {
      const shouldResume = current.goal.status === "active";
      const settled = shouldResume
        ? await port.pause({
            goalId: current.goal.goalId,
            expectedGoalRevision: current.goal.revision,
            idempotencyKey: `${key}:pause`,
          })
        : current;
      if (shouldResume) acceptModel(settled);
      const linked = await retrySettlingGoalMutation(() => operation(key, settled));
      acceptModel(linked);
      if (!shouldResume) return linked;
      const resumed = await port.resume({
        goalId: linked.goal.goalId,
        expectedGoalRevision: linked.goal.revision,
        idempotencyKey: `${key}:resume`,
      });
      acceptModel(resumed);
      return resumed;
    },
    [acceptModel, port],
  );

  useEffect(() => {
    epochRef.current += 1;
    refreshSequenceRef.current += 1;
    mutationSequenceRef.current += 1;
    mutationInFlightRef.current = false;
    retainedKeysRef.current.clear();
    modelRef.current = undefined;
    setModel(undefined);
    setRevisions([]);
    setEvidence([]);
    setError(undefined);
    setBusyAction(undefined);
    if (!visible || effectiveGoalId === undefined) return;
    const epoch = epochRef.current;
    let observationId: string | undefined;
    void port
      .read({ goalId: effectiveGoalId })
      .then(async (next) => {
        if (epoch !== epochRef.current) return;
        acceptModel(next);
        const observed = await port.observe({
          goalId: effectiveGoalId,
          expectedGoalRevision: next.goal.revision,
        });
        if (epoch !== epochRef.current) {
          await port.unobserve({ observationId: observed.observationId }).catch(() => undefined);
          return;
        }
        observationId = observed.observationId;
        await refreshRef.current();
      })
      .catch(() => {
        if (epoch === epochRef.current) setError(actionErrorMessage("read"));
      });
    return () => {
      epochRef.current += 1;
      if (observationId !== undefined)
        void port.unobserve({ observationId }).catch(() => undefined);
    };
  }, [acceptModel, effectiveGoalId, port, visible]);

  /** Thread scope 切换清理 standalone Plan；同 Thread 的 Goal catalog 回读不得抹掉刚创建的 Plan。 */
  useEffect(() => {
    if (planModelRef.current?.plan.ownerThreadId === ownerThreadId) return;
    planModelRef.current = undefined;
    setPlanModel(undefined);
  }, [ownerThreadId]);

  /**
   * 事件只携带 identity：落后或已包含在当前权威快照中的事件直接丢弃；前进或 gap 均统一回读，
   * 不从事件正文推导状态，也不允许旧通知把较新的 mutation ACK 拉回历史投影。
   */
  useEffect(
    () =>
      port.subscribe((event) => {
        if (!visible) return;
        if (event.goalId === effectiveGoalId) {
          const current = modelRef.current;
          if (
            current !== undefined &&
            (event.goalRevision < current.goal.revision ||
              event.eventSequence <= modelEventSequence(current))
          )
            return;
          void refresh();
          return;
        }
        // 另一个受信 UI 入口完成首次创建时，Thread catalog 可能尚未回读 activeGoalId；
        // 只接纳同 owner 的 revision 0 changed 事件，再由 read/observe 验证完整聚合。
        if (
          effectiveGoalId === undefined &&
          ownerThreadId !== undefined &&
          event.method === "goal/changed" &&
          event.goalRevision === 0 &&
          event.ownerThreadId === ownerThreadId
        ) {
          setProvisionalGoal({ goalId: event.goalId, ownerThreadId });
        }
      }),
    [effectiveGoalId, ownerThreadId, port, refresh, visible],
  );

  return useMemo(
    () => ({
      model:
        model?.goal.ownerThreadId === ownerThreadId || ownerThreadId === undefined
          ? model
          : undefined,
      planModel:
        planModel?.plan.ownerThreadId === ownerThreadId || ownerThreadId === undefined
          ? planModel
          : undefined,
      revisions,
      evidence,
      loading,
      error,
      busyAction,
      refresh,
      create,
      createPlan,
      pause: () =>
        mutate("pause", "pause", (key, current) =>
          port.pause({
            goalId: current.goal.goalId,
            expectedGoalRevision: current.goal.revision,
            idempotencyKey: key,
          }),
        ),
      resume: () =>
        mutate("resume", "resume", (key, current) =>
          port.resume({
            goalId: current.goal.goalId,
            expectedGoalRevision: current.goal.revision,
            idempotencyKey: key,
          }),
        ),
      stop: () =>
        mutate("stop", "stop", (key, current) =>
          port.stop({
            goalId: current.goal.goalId,
            expectedGoalRevision: current.goal.revision,
            idempotencyKey: key,
          }),
        ),
      respondInput: (response) =>
        mutate("respond_input", response, (key, current) => {
          if (current.inputRequest === null)
            return Promise.reject(new Error("input request missing"));
          return port.respondInput({
            goalId: current.goal.goalId,
            requestId: current.inputRequest.requestId,
            response,
            expectedGoalRevision: current.goal.revision,
            idempotencyKey: key,
          });
        }),
      saveDraft: (draft) =>
        mutatePlan("save_draft", JSON.stringify(draft), (key, current) =>
          port.saveDraft({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            draft,
            expectedPlanRevision: current.plan.revision,
            idempotencyKey: key,
          }),
        ),
      discardDraft: () =>
        mutatePlan("discard_draft", "discard", (key, current) =>
          port.discardDraft({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            expectedPlanRevision: current.plan.revision,
            idempotencyKey: key,
          }),
        ),
      propose: () =>
        mutatePlan("propose", "propose", (key, current) =>
          port.propose({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            expectedPlanRevision: current.plan.revision,
            idempotencyKey: key,
          }),
        ),
      approve: () =>
        mutatePlan("approve", planModel?.revision?.planRevisionId ?? "missing", (key, current) => {
          if (current.revision === null) return Promise.reject(new Error("plan revision missing"));
          return port.approve({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            planRevisionId: current.revision.planRevisionId,
            planHash: current.revision.planHash,
            expectedPlanRevision: current.plan.revision,
            idempotencyKey: key,
          });
        }),
      execute: () =>
        mutatePlan("execute", planModel?.revision?.planRevisionId ?? "missing", (key, current) => {
          if (current.revision === null) return Promise.reject(new Error("plan revision missing"));
          return port.execute({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            planRevisionId: current.revision.planRevisionId,
            planHash: current.revision.planHash,
            expectedPlanRevision: current.plan.revision,
            idempotencyKey: key,
          });
        }),
      attachPlan: () =>
        mutate("attach_plan", planModel?.revision?.planRevisionId ?? "missing", (key, current) => {
          const selectedPlan = planModelRef.current;
          if (
            selectedPlan?.revision === null ||
            selectedPlan?.revision === undefined ||
            selectedPlan.plan.status !== "approved"
          )
            return Promise.reject(new Error("approved plan revision missing"));
          return replaceGoalPlanBinding(key, current, (mutationKey, settled) =>
            port.attachPlan({
              goalId: settled.goal.goalId,
              planId: selectedPlan.plan.planId,
              planRevisionId: selectedPlan.revision!.planRevisionId,
              planHash: selectedPlan.revision!.planHash,
              expectedGoalRevision: settled.goal.revision,
              idempotencyKey: mutationKey,
            }),
          );
        }),
      detachPlan: () =>
        mutate("detach_plan", model?.goal.activePlanRevisionId ?? "missing", (key, current) =>
          replaceGoalPlanBinding(key, current, (mutationKey, settled) =>
            port.detachPlan({
              goalId: settled.goal.goalId,
              expectedGoalRevision: settled.goal.revision,
              idempotencyKey: mutationKey,
            }),
          ),
        ),
      reject: () =>
        mutatePlan("reject", planModel?.revision?.planRevisionId ?? "missing", (key, current) => {
          return port.reject({
            ownerThreadId: current.plan.ownerThreadId,
            planId: current.plan.planId,
            expectedPlanRevision: current.plan.revision,
            idempotencyKey: key,
          });
        }),
    }),
    [
      busyAction,
      create,
      createPlan,
      error,
      evidence,
      loading,
      model,
      planModel,
      mutate,
      mutatePlan,
      ownerThreadId,
      port,
      refresh,
      replaceGoalPlanBinding,
      revisions,
    ],
  );
}
