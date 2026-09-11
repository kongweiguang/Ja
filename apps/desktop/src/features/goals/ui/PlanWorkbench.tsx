// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  AlertCircle,
  Check,
  ChevronDown,
  Circle,
  CirclePause,
  CirclePlay,
  GitCompare,
  LoaderCircle,
  Pencil,
  Plus,
  Save,
  Play,
  RotateCcw,
  Square,
  ShieldCheck,
  Target,
  Trash2,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type ReactElement } from "react";
import type { GoalMutationAction } from "../application/ports";
import {
  goalPhaseLabel,
  planStatusLabel,
  planProgressFromRevision,
  planStepStatusLabel,
  type AcceptanceEvidence,
  type GoalReadModel,
  type PlanDraft,
  type PlanDraftCriterion,
  type PlanDraftStep,
  type PlanReadModel,
  type PlanRevision,
} from "../domain/goalModel";
import { goalPrimaryAction } from "./GoalStatusBar";
import "./goals.css";

/* eslint-disable react-refresh/only-export-components -- 结构化计划策略与唯一消费组件必须同步演进。 */

export interface PlanWorkbenchProps {
  readonly model?: GoalReadModel;
  readonly planModel?: PlanReadModel;
  readonly revisions?: readonly PlanRevision[];
  readonly evidence?: readonly AcceptanceEvidence[];
  readonly loading?: boolean;
  readonly error?: string;
  readonly busyAction?: GoalMutationAction;
  readonly onRetry?: () => void;
  readonly onSaveDraft?: (draft: PlanDraft) => void | boolean | Promise<void | boolean>;
  readonly onDiscardDraft?: () => void | boolean | Promise<void | boolean>;
  readonly onPropose?: () => void | boolean | Promise<void | boolean>;
  /** 完成编辑后生成不可变 revision；不代表已经授权执行。 */
  readonly onFinalizePlan?: () => void | boolean | Promise<void | boolean>;
  readonly onExecute?: () => void | boolean | Promise<void | boolean>;
  readonly onAttachPlan?: () => void | boolean | Promise<void | boolean>;
  readonly onDetachPlan?: () => void | boolean | Promise<void | boolean>;
  readonly onReject?: () => void | boolean | Promise<void | boolean>;
  readonly onPause?: () => void | boolean | Promise<void | boolean>;
  readonly onResume?: () => void | boolean | Promise<void | boolean>;
  readonly onPausePlan?: () => void | boolean | Promise<void | boolean>;
  readonly onResumePlan?: () => void | boolean | Promise<void | boolean>;
  readonly onStopPlan?: () => void | boolean | Promise<void | boolean>;
  readonly onBeginEdit?: () => void | boolean | Promise<void | boolean>;
  readonly onCancelEdit?: () => void | boolean | Promise<void | boolean>;
  readonly onContinue?: () => void | boolean | Promise<void | boolean>;
}

/** 直接编辑从服务端 draft 或冻结 revision 复制值，稳定 ID 在保存前后都保持不变。 */
export function editableDraft(model: GoalReadModel | PlanReadModel): PlanDraft {
  const goalModel = "goal" in model ? model : undefined;
  const resolvedPlan: PlanReadModel | undefined = "goal" in model ? linkedPlanView(model) : model;
  if (resolvedPlan?.draft !== null && resolvedPlan?.draft !== undefined) return resolvedPlan.draft;
  const plan = resolvedPlan?.revision ?? null;
  const planState = resolvedPlan?.plan;
  const now = new Date().toISOString();
  return {
    draftId: `draft-${planState?.planId ?? goalModel?.goal.goalId ?? "new"}`,
    planId: planState?.planId ?? plan?.planId ?? `plan_draft_${goalModel?.goal.goalId ?? "new"}`,
    draftRevision: 0,
    basePlanRevisionId: plan?.planRevisionId ?? null,
    objective: plan?.objective ?? planState?.objective ?? goalModel?.goal.objective ?? "",
    scope: plan?.scope ?? [],
    nonGoals: plan?.nonGoals ?? [],
    constraints: plan?.constraints ?? [],
    steps:
      plan?.steps.map(({ stepId, title, description, required, dependencyStepIds }) => ({
        stepId,
        title,
        description,
        required,
        dependencyStepIds,
      })) ?? [],
    acceptanceCriteria:
      plan?.acceptanceCriteria.map(({ criterionId, description, required }) => ({
        criterionId,
        description,
        required,
      })) ?? [],
    risks: plan?.risks ?? [],
    verificationStrategy: plan?.verificationStrategy ?? [],
    updatedAt: now,
  };
}

/** Goal read 中的 linked Plan 仅转换视图形状，不生成新的 Goal 或 Plan identity。 */
function linkedPlanView(model: GoalReadModel): PlanReadModel | undefined {
  if (model.planState === null) return undefined;
  return {
    plan: model.planState,
    revision: model.plan,
    draft: model.draft,
    revisionHydrationRequired: false,
    progress: planProgressFromRevision(model.plan),
    approvedPlanRevisionId:
      model.plan?.approvedAt === null ? null : (model.plan?.planRevisionId ?? null),
    eventSequence: model.planEventSequence ?? model.planState.revision,
  };
}

/** 列表输入按行结构化，空行仅是编辑噪声，不会进入权威 draft。 */
function lines(value: string): string[] {
  return value
    .split(/\r?\n/u)
    .map((item) => item.trim())
    .filter(Boolean);
}

/** 新增本地稳定 ID 不依赖数组位置，重排或切换 required 不改变步骤身份。 */
function localId(prefix: "step" | "criterion"): string {
  const random = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2);
  return `${prefix}_draft_${random}`;
}

/** 比较 draft 定义而忽略服务端更新时间，避免同一 ACK 被误报为外部冲突。 */
function draftContentKey(draft: PlanDraft): string {
  return JSON.stringify(draft, (key, value: unknown) => (key === "updatedAt" ? undefined : value));
}

type DraftSaveOperation = NonNullable<PlanWorkbenchProps["onSaveDraft"]>;

interface DraftSaveJob {
  readonly scopeKey: string;
  readonly draft: PlanDraft;
  readonly editRevision: number;
  readonly save: DraftSaveOperation;
  readonly promise: Promise<boolean>;
  readonly resolve: (accepted: boolean) => void;
}

interface DraftSaveQueue {
  active: DraftSaveJob | null;
  queued: DraftSaveJob | null;
  worker: Promise<void> | null;
}

interface RetainedLocalDraft {
  readonly draft: PlanDraft;
  readonly dirty: boolean;
}

/**
 * 跨 Workbench 卸载保留仍未确认的本地草稿；只按 Thread/Plan identity 隔离，绝不作为服务端真相。
 * 保存成功或用户明确丢弃后会删除，避免把旧版本重新注入新 Plan。
 */
const retainedDraftsBySave = new WeakMap<DraftSaveOperation, Map<string, RetainedLocalDraft>>();

/** 为同一个真实保存回调建立弱引用注册表，卸载重挂载可恢复草稿且不会污染其他会话。 */
function retainedDraftMap(save: DraftSaveOperation): Map<string, RetainedLocalDraft> {
  const existing = retainedDraftsBySave.get(save);
  if (existing !== undefined) return existing;
  const created = new Map<string, RetainedLocalDraft>();
  retainedDraftsBySave.set(save, created);
  return created;
}

/** Diff 给出字段和具体增删内容，便于用户在执行前识别真实变化而非只看到“版本不同”。 */
export function revisionDiff(current: PlanRevision, prior: PlanRevision): readonly string[] {
  const changes: string[] = [];
  if (current.objective !== prior.objective)
    changes.push(`目标：${prior.objective} → ${current.objective}`);
  const compareList = (label: string, next: readonly string[], before: readonly string[]): void => {
    const added = next.filter((item) => !before.includes(item));
    const removed = before.filter((item) => !next.includes(item));
    if (added.length > 0) changes.push(`${label}：新增「${added.join("、")}」`);
    if (removed.length > 0) changes.push(`${label}：移除「${removed.join("、")}」`);
  };
  compareList("范围", current.scope, prior.scope);
  compareList("非目标", current.nonGoals, prior.nonGoals);
  compareList("约束", current.constraints, prior.constraints);
  compareList("风险", current.risks, prior.risks);
  compareList("验证策略", current.verificationStrategy, prior.verificationStrategy);
  const priorSteps = new Map(prior.steps.map((step) => [step.stepId, step]));
  const currentSteps = new Map(current.steps.map((step) => [step.stepId, step]));
  current.steps.forEach((step) => {
    const before = priorSteps.get(step.stepId);
    if (before === undefined) changes.push(`步骤：新增「${step.title}」`);
    else if (
      before.title !== step.title ||
      before.description !== step.description ||
      before.required !== step.required ||
      JSON.stringify(before.dependencyStepIds) !== JSON.stringify(step.dependencyStepIds)
    )
      changes.push(`步骤：更新「${before.title}」为「${step.title}」`);
  });
  prior.steps.forEach((step) => {
    if (!currentSteps.has(step.stepId)) changes.push(`步骤：移除「${step.title}」`);
  });
  const priorCriteria = new Map(
    prior.acceptanceCriteria.map((criterion) => [criterion.criterionId, criterion]),
  );
  const currentCriteria = new Map(
    current.acceptanceCriteria.map((criterion) => [criterion.criterionId, criterion]),
  );
  current.acceptanceCriteria.forEach((criterion) => {
    const before = priorCriteria.get(criterion.criterionId);
    if (before === undefined) changes.push(`验收条件：新增「${criterion.description}」`);
    else if (before.description !== criterion.description || before.required !== criterion.required)
      changes.push(`验收条件：更新「${before.description}」`);
  });
  prior.acceptanceCriteria.forEach((criterion) => {
    if (!currentCriteria.has(criterion.criterionId))
      changes.push(`验收条件：移除「${criterion.description}」`);
  });
  return changes;
}

/** Plan Workbench 保持内容优先：单页内直接编辑、审查 revision、步骤依赖、验收与证据。 */
export function PlanWorkbench({
  model,
  planModel,
  revisions = [],
  evidence = [],
  loading = false,
  error,
  busyAction,
  onRetry,
  onSaveDraft,
  onDiscardDraft,
  onPropose,
  onFinalizePlan,
  onExecute,
  onAttachPlan,
  onDetachPlan,
  onPause,
  onResume,
  onPausePlan,
  onResumePlan,
  onStopPlan,
  onBeginEdit,
  onCancelEdit,
  onContinue,
}: PlanWorkbenchProps): ReactElement {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<PlanDraft>();
  const [compareRevisionId, setCompareRevisionId] = useState<string>();
  const [draftDirty, setDraftDirty] = useState(false);
  const [draftSaveState, setDraftSaveState] = useState<"idle" | "saving" | "saved" | "error">(
    "idle",
  );
  const [startingEdit, setStartingEdit] = useState(false);
  const localEditRevisionRef = useRef(0);
  const draftChangedRef = useRef(false);
  const draftRef = useRef<PlanDraft | undefined>(undefined);
  const draftDirtyRef = useRef(false);
  const localDraftsRef = useRef(
    new Map<string, { readonly draft: PlanDraft; readonly dirty: boolean }>(),
  );
  const draftSaveQueueRef = useRef<DraftSaveQueue>({ active: null, queued: null, worker: null });
  const currentPlanKeyRef = useRef<string | undefined>(undefined);
  const unmountedPlanKeyRef = useRef<string | undefined>(undefined);
  const mountedRef = useRef(false);
  const onSaveDraftRef = useRef<PlanWorkbenchProps["onSaveDraft"]>(onSaveDraft);
  const saveOperationByPlanRef = useRef(new Map<string, DraftSaveOperation>());
  const enqueueDraftSaveRef = useRef<
    (
      draft: PlanDraft,
      scopeKey: string,
      editRevision: number,
      saveOverride?: DraftSaveOperation,
    ) => Promise<boolean>
  >(() => Promise.resolve(true));
  const editButtonRef = useRef<HTMLButtonElement>(null);
  const resumeAfterEditRef = useRef(false);
  const renderedPlanKeyRef = useRef<string | undefined>(undefined);
  const resolvedPlanModel = useMemo(
    () => planModel ?? (model === undefined ? undefined : linkedPlanView(model)),
    [model, planModel],
  );
  const resolvedPlanKey =
    resolvedPlanModel === undefined
      ? undefined
      : `${resolvedPlanModel.plan.ownerThreadId}:${resolvedPlanModel.plan.planId}`;
  currentPlanKeyRef.current = resolvedPlanKey;

  useEffect(() => {
    draftRef.current = draft;
    draftDirtyRef.current = draftDirty;
  }, [draft, draftDirty]);

  useEffect(() => {
    onSaveDraftRef.current = onSaveDraft;
  }, [onSaveDraft]);

  useEffect(() => {
    if (resolvedPlanKey === undefined || onSaveDraft === undefined) return;
    saveOperationByPlanRef.current.set(resolvedPlanKey, onSaveDraft);
  }, [onSaveDraft, resolvedPlanKey]);

  /** 保存队列只允许一个真实回调在途；旧 ACK 不能清理新代次的 dirty/error 状态。 */
  const drainDraftSaveQueue = useCallback((): void => {
    const queue = draftSaveQueueRef.current;
    if (queue.worker !== null) return;
    queue.worker = (async () => {
      while (queue.queued !== null) {
        const job = queue.queued;
        queue.queued = null;
        queue.active = job;
        let accepted = false;
        try {
          accepted = (await Promise.resolve(job.save(job.draft))) !== false;
        } catch {
          accepted = false;
        }
        if (queue.active === job) queue.active = null;

        const currentScope = currentPlanKeyRef.current;
        const sameScope =
          currentScope === job.scopeKey ||
          (!mountedRef.current &&
            currentScope === undefined &&
            unmountedPlanKeyRef.current === job.scopeKey);
        const newerQueued = (queue.queued as DraftSaveJob | null)?.scopeKey === job.scopeKey;
        const newerEdit = sameScope && localEditRevisionRef.current > job.editRevision;
        const superseded = newerQueued || newerEdit;
        const currentDraftIsUnconfirmed =
          sameScope && (draftDirtyRef.current || draftChangedRef.current);
        const retainLocalDraft =
          (!accepted && (!sameScope || currentDraftIsUnconfirmed)) ||
          (superseded && (newerQueued || currentDraftIsUnconfirmed));
        if (retainLocalDraft) {
          const latestDraft =
            sameScope && draftRef.current !== undefined ? draftRef.current : job.draft;
          const retained = { draft: latestDraft, dirty: true };
          localDraftsRef.current.set(job.scopeKey, retained);
          retainedDraftMap(job.save).set(job.scopeKey, retained);
        }

        if (accepted && !superseded) {
          localDraftsRef.current.delete(job.scopeKey);
          retainedDraftMap(job.save).delete(job.scopeKey);
          if (mountedRef.current && sameScope) {
            draftDirtyRef.current = false;
            setDraftDirty(false);
            draftChangedRef.current = false;
            setDraftSaveState("saved");
          }
        } else if (!accepted && !superseded) {
          if (mountedRef.current && sameScope) setDraftSaveState("error");
        } else if (mountedRef.current && sameScope) {
          setDraftSaveState(newerQueued ? "saving" : "idle");
        }
        job.resolve(accepted);
      }
      queue.worker = null;
    })();
  }, []);

  /** 将新内容合并为最新待保存项，避免同一 Plan 的保存请求并发或旧请求覆盖新输入。 */
  const enqueueDraftSave = useCallback(
    (
      nextDraft: PlanDraft,
      scopeKey: string,
      editRevision: number,
      saveOverride?: DraftSaveOperation,
    ): Promise<boolean> => {
      const save = saveOverride ?? onSaveDraftRef.current;
      if (save === undefined) return Promise.resolve(true);
      const queue = draftSaveQueueRef.current;
      const contentKey = draftContentKey(nextDraft);
      const existing = [queue.active, queue.queued].find(
        (job) =>
          job !== null && job.scopeKey === scopeKey && draftContentKey(job.draft) === contentKey,
      );
      if (existing !== undefined && existing !== null) return existing.promise;

      if (queue.queued !== null) queue.queued.resolve(false);
      let resolveJob!: (accepted: boolean) => void;
      const promise = new Promise<boolean>((resolve) => {
        resolveJob = resolve;
      });
      queue.queued = {
        scopeKey,
        draft: nextDraft,
        editRevision,
        save,
        promise,
        resolve: resolveJob,
      };
      if (mountedRef.current && currentPlanKeyRef.current === scopeKey) setDraftSaveState("saving");
      drainDraftSaveQueue();
      return promise;
    },
    [drainDraftSaveQueue],
  );
  enqueueDraftSaveRef.current = enqueueDraftSave;

  /** 自动保存使用当前 scope 与编辑代次；无 scope 时不向服务端发出无主请求。 */
  const persistDraft = useCallback(
    (nextDraft: PlanDraft): Promise<boolean> => {
      const scopeKey = currentPlanKeyRef.current;
      if (scopeKey === undefined) return Promise.resolve(false);
      return enqueueDraftSave(nextDraft, scopeKey, localEditRevisionRef.current);
    },
    [enqueueDraftSave],
  );

  /** 用户明确取消或放弃后清理该 scope 的本地保留，避免已丢弃内容在下一次挂载时复活。 */
  const clearRetainedDraft = useCallback((scopeKey: string): void => {
    localDraftsRef.current.delete(scopeKey);
    const save = saveOperationByPlanRef.current.get(scopeKey) ?? onSaveDraftRef.current;
    if (save !== undefined) retainedDraftMap(save).delete(scopeKey);
  }, []);

  /** 取消编辑时只丢弃尚未启动的 queued save；在途请求无法撤销，靠新编辑代次隔离迟到 ACK。 */
  const invalidateQueuedDraftSave = useCallback((scopeKey: string): void => {
    const queue = draftSaveQueueRef.current;
    if (queue.queued?.scopeKey !== scopeKey) return;
    queue.queued.resolve(false);
    queue.queued = null;
  }, []);

  /** 定稿前持续 flush 最新本地代次，只有当前内容得到服务端确认后才可生成 revision。 */
  const flushLatestDraft = useCallback(
    async (scopeKey: string): Promise<boolean> => {
      while (true) {
        if (currentPlanKeyRef.current !== scopeKey) return false;
        const latestDraft = draftRef.current;
        if (latestDraft === undefined) return false;
        const editRevision = localEditRevisionRef.current;
        const contentKey = draftContentKey(latestDraft);
        const needsSave =
          draftDirtyRef.current ||
          draftChangedRef.current ||
          draftSaveQueueRef.current.active?.scopeKey === scopeKey ||
          draftSaveQueueRef.current.queued?.scopeKey === scopeKey;
        if (needsSave && !(await enqueueDraftSave(latestDraft, scopeKey, editRevision)))
          return false;
        if (currentPlanKeyRef.current !== scopeKey) return false;
        const currentDraft = draftRef.current;
        if (
          currentDraft !== undefined &&
          localEditRevisionRef.current === editRevision &&
          draftContentKey(currentDraft) === contentKey
        )
          return true;
      }
    },
    [enqueueDraftSave],
  );

  /** 输入稳定后自动落 draft；卸载前的清理逻辑会把尚未触发的 debounce 立即入队。 */
  useEffect(() => {
    if (!editing || !draftDirty || draft === undefined || onSaveDraft === undefined) return;
    const timer = globalThis.setTimeout(() => {
      void persistDraft(draft);
    }, 500);
    return () => globalThis.clearTimeout(timer);
  }, [draft, draftDirty, editing, onSaveDraft, persistDraft]);

  /**
   * 同一 Plan 的 read/observe 可能在用户点击编辑后迟到；服务端尚无 draft 时保留本地编辑，
   * 只有切换 Plan 或收到权威 draft 才重建表单，避免把刚出现的输入框竞态卸载。
   */
  useEffect(() => {
    if (resolvedPlanModel === undefined) return;
    const planKey = `${resolvedPlanModel.plan.ownerThreadId}:${resolvedPlanModel.plan.planId}`;
    const planChanged = renderedPlanKeyRef.current !== planKey;
    const previousPlanKey = renderedPlanKeyRef.current;
    if (
      planChanged &&
      previousPlanKey !== undefined &&
      draftRef.current !== undefined &&
      (draftDirtyRef.current ||
        draftChangedRef.current ||
        draftSaveQueueRef.current.active?.scopeKey === previousPlanKey ||
        draftSaveQueueRef.current.queued?.scopeKey === previousPlanKey)
    ) {
      const latestDraft = draftRef.current;
      if (latestDraft !== undefined) {
        const retained = { draft: latestDraft, dirty: true };
        localDraftsRef.current.set(previousPlanKey, retained);
        const save = saveOperationByPlanRef.current.get(previousPlanKey) ?? onSaveDraftRef.current;
        if (save !== undefined) {
          retainedDraftMap(save).set(previousPlanKey, retained);
          // 切换 Thread 会清理 debounce；立即入队，确保最后一次输入不会只存在内存中。
          void enqueueDraftSaveRef.current(
            latestDraft,
            previousPlanKey,
            localEditRevisionRef.current,
            save,
          );
        }
      }
    }
    renderedPlanKeyRef.current = planKey;
    if (planChanged) {
      const save = onSaveDraftRef.current;
      const retained =
        localDraftsRef.current.get(planKey) ??
        (save === undefined ? undefined : retainedDraftMap(save).get(planKey));
      const nextDraft = retained?.draft ?? editableDraft(resolvedPlanModel);
      setDraft(nextDraft);
      draftRef.current = nextDraft;
      draftDirtyRef.current = retained?.dirty ?? false;
      setDraftDirty(retained?.dirty ?? false);
      draftChangedRef.current = retained?.dirty ?? false;
      setDraftSaveState(
        retained === undefined
          ? "idle"
          : draftSaveQueueRef.current.active?.scopeKey === planKey ||
              draftSaveQueueRef.current.queued?.scopeKey === planKey
            ? "saving"
            : "error",
      );
      setEditing(retained?.dirty ?? resolvedPlanModel.draft !== null);
      return;
    }
    if (resolvedPlanModel.draft !== null) {
      // 同一 Plan 的服务端 ACK 不能覆盖用户在请求期间继续输入的本地版本。
      if (
        draftDirtyRef.current ||
        draftChangedRef.current ||
        draftSaveQueueRef.current.active?.scopeKey === planKey ||
        draftSaveQueueRef.current.queued?.scopeKey === planKey
      ) {
        if (
          draftRef.current === undefined ||
          draftContentKey(draftRef.current) !== draftContentKey(resolvedPlanModel.draft)
        )
          setDraftSaveState("error");
        return;
      }
      const nextDraft = editableDraft(resolvedPlanModel);
      setDraft(nextDraft);
      draftRef.current = nextDraft;
      draftDirtyRef.current = false;
      setDraftDirty(false);
      draftChangedRef.current = false;
      setDraftSaveState("idle");
      setEditing(true);
      return;
    }
    setDraft((current) => current ?? editableDraft(resolvedPlanModel));
  }, [resolvedPlanModel]);

  useEffect(() => {
    resumeAfterEditRef.current = false;
  }, [resolvedPlanModel?.plan.ownerThreadId, resolvedPlanModel?.plan.planId]);

  /** 组件卸载时立即提交最后一个本地代次；即使 ACK 迟到也只能更新原 scope 的保留记录。 */
  useEffect(() => {
    mountedRef.current = true;
    const saveQueue = draftSaveQueueRef.current;
    const localDrafts = localDraftsRef.current;
    const saveOperations = saveOperationByPlanRef.current;
    return () => {
      mountedRef.current = false;
      const scopeKey = currentPlanKeyRef.current;
      const latestDraft = draftRef.current;
      if (
        scopeKey !== undefined &&
        latestDraft !== undefined &&
        (draftDirtyRef.current ||
          draftChangedRef.current ||
          saveQueue.active?.scopeKey === scopeKey ||
          saveQueue.queued?.scopeKey === scopeKey)
      ) {
        const retained = { draft: latestDraft, dirty: true };
        localDrafts.set(scopeKey, retained);
        const save = saveOperations.get(scopeKey) ?? onSaveDraftRef.current;
        if (save !== undefined) retainedDraftMap(save).set(scopeKey, retained);
        unmountedPlanKeyRef.current = scopeKey;
        void enqueueDraftSaveRef.current(latestDraft, scopeKey, localEditRevisionRef.current, save);
      }
      currentPlanKeyRef.current = undefined;
    };
  }, []);

  const compareRevision = revisions.find(
    (revision) => revision.planRevisionId === compareRevisionId,
  );
  const changes = useMemo(
    () =>
      resolvedPlanModel?.revision !== null &&
      resolvedPlanModel?.revision !== undefined &&
      compareRevision !== undefined
        ? revisionDiff(resolvedPlanModel.revision, compareRevision)
        : [],
    [compareRevision, resolvedPlanModel?.revision],
  );

  /** 取消只丢弃局部编辑，服务端已有 draft 必须通过显式“放弃草稿”命令处理。 */
  const cancelLocalEdit = async (): Promise<void> => {
    if (resolvedPlanModel === undefined) return;
    const scopeKey = `${resolvedPlanModel.plan.ownerThreadId}:${resolvedPlanModel.plan.planId}`;
    const shouldResume = resumeAfterEditRef.current && !draftChangedRef.current;
    localEditRevisionRef.current += 1;
    invalidateQueuedDraftSave(scopeKey);
    clearRetainedDraft(scopeKey);
    const nextDraft = editableDraft(resolvedPlanModel);
    setDraft(nextDraft);
    draftRef.current = nextDraft;
    draftDirtyRef.current = false;
    setDraftDirty(false);
    draftChangedRef.current = false;
    setDraftSaveState("idle");
    setEditing(false);
    if (shouldResume && onCancelEdit !== undefined) {
      await onCancelEdit();
      resumeAfterEditRef.current = false;
    }
    window.requestAnimationFrame(() => editButtonRef.current?.focus());
  };

  /** 已批准版本先等待服务端暂停 ACK，再开放本地编辑，避免执行与改版并发。 */
  const beginEdit = async (): Promise<void> => {
    if (resolvedPlanModel === undefined || startingEdit) return;
    // 用户可能在首次同步 effect 执行前立即点击；先锁定当前 identity，避免该 effect 反向关闭编辑态。
    renderedPlanKeyRef.current = `${resolvedPlanModel.plan.ownerThreadId}:${resolvedPlanModel.plan.planId}`;
    setStartingEdit(true);
    try {
      if (onBeginEdit !== undefined) {
        if ((await onBeginEdit()) === false) return;
        resumeAfterEditRef.current = true;
      }
      const nextDraft = editableDraft(resolvedPlanModel);
      setDraft(nextDraft);
      draftRef.current = nextDraft;
      draftDirtyRef.current = false;
      setDraftDirty(false);
      draftChangedRef.current = false;
      setDraftSaveState("idle");
      setEditing(true);
    } finally {
      setStartingEdit(false);
    }
  };

  if (loading && resolvedPlanModel === undefined)
    return (
      <div className="ja-plan-state" role="status">
        <LoaderCircle className="ja-goal-spin" aria-hidden="true" />
        <span>正在读取计划</span>
      </div>
    );
  if (resolvedPlanModel === undefined && model !== undefined)
    return <GoalOnlyView model={model} evidence={evidence} />;
  if (resolvedPlanModel === undefined)
    return (
      <div className="ja-plan-state" role={error === undefined ? "status" : "alert"}>
        <AlertCircle aria-hidden="true" />
        <strong>{error ?? "当前会话没有计划"}</strong>
        {error !== undefined && onRetry !== undefined ? (
          <button type="button" onClick={onRetry}>
            重试
          </button>
        ) : null}
      </div>
    );

  const goal = model?.goal;
  const planState = resolvedPlanModel.plan;
  const plan = resolvedPlanModel.revision;
  const linkedToGoal = goal?.activePlanId === planState.planId;
  const evaluation = linkedToGoal ? (model?.evaluation ?? null) : null;
  const isBusy = busyAction !== undefined;
  const currentStep = plan?.steps.find(
    (step) => step.stepId === (linkedToGoal ? goal?.currentStepId : null),
  );
  const selectedDraft = draft ?? editableDraft(resolvedPlanModel);
  const primaryAction =
    linkedToGoal && goal !== undefined ? goalPrimaryAction(goal, evaluation) : undefined;

  const finishEditing = async (): Promise<void> => {
    const scopeKey = currentPlanKeyRef.current;
    if (draft === undefined || onSaveDraft === undefined || scopeKey === undefined) return;
    if (!(await flushLatestDraft(scopeKey))) return;
    const finalize = onFinalizePlan ?? onPropose;
    if (finalize !== undefined && (await finalize()) === false) return;
    setEditing(false);
    draftDirtyRef.current = false;
    setDraftDirty(false);
    draftChangedRef.current = false;
    resumeAfterEditRef.current = false;
  };

  return (
    <main
      className="ja-plan-workbench"
      aria-busy={isBusy || undefined}
      data-goal-ui="plan-workbench"
      data-goal-id={goal?.goalId}
      data-goal-phase={goal?.phase}
      data-plan-id={planState.planId}
      data-plan-revision-id={plan?.planRevisionId}
    >
      <header className="ja-plan-header">
        <div className="ja-plan-header__copy">
          <span>{planStatusLabel(planState.status)}</span>
          <h2 title={planState.objective}>{planState.objective}</h2>
          <small>{plan === null ? "尚无已确认版本" : `第 ${plan.revisionNumber} 版`}</small>
        </div>
        <div className="ja-plan-header__actions">
          {!editing &&
          onSaveDraft !== undefined &&
          (resolvedPlanModel.draft !== null ||
            planState.status === "draft" ||
            planState.status === "awaiting_approval" ||
            goal?.phase === "paused" ||
            onBeginEdit !== undefined) ? (
            <button
              ref={editButtonRef}
              type="button"
              className="ja-plan-button is-secondary"
              disabled={isBusy || startingEdit}
              aria-busy={startingEdit || undefined}
              onClick={() => void beginEdit()}
            >
              <Pencil aria-hidden="true" />
              编辑计划
            </button>
          ) : null}
          {!editing &&
          (planState.status === "draft" ||
            planState.status === "awaiting_approval" ||
            planState.status === "approved") &&
          plan !== null &&
          (onAttachPlan !== undefined || onExecute !== undefined) ? (
            <button
              type="button"
              className="ja-plan-button is-primary"
              disabled={isBusy}
              onClick={() => void (onAttachPlan ?? onExecute)?.()}
            >
              <Play aria-hidden="true" />
              {onAttachPlan !== undefined ? "用于当前目标" : "执行"}
            </button>
          ) : null}
          {!editing && goal?.activePlanId === planState.planId && onDetachPlan !== undefined ? (
            <button
              type="button"
              className="ja-plan-button is-secondary"
              disabled={isBusy}
              onClick={() => void onDetachPlan()}
            >
              移出当前目标
            </button>
          ) : null}
          {primaryAction === "pause" && onPause !== undefined ? (
            <button
              type="button"
              className="ja-plan-icon-button"
              aria-label="暂停目标"
              title="暂停目标"
              disabled={isBusy}
              onClick={() => void onPause()}
            >
              <CirclePause aria-hidden="true" />
            </button>
          ) : null}
          {primaryAction === "resume" && onResume !== undefined ? (
            <button
              type="button"
              className="ja-plan-icon-button"
              aria-label="恢复目标"
              title="恢复目标"
              disabled={isBusy}
              onClick={() => void onResume()}
            >
              <CirclePlay aria-hidden="true" />
            </button>
          ) : null}
          {primaryAction === "continue" && onContinue !== undefined ? (
            <button
              type="button"
              className="ja-plan-button is-primary"
              disabled={isBusy}
              onClick={() => void onContinue()}
            >
              继续处理
            </button>
          ) : null}
          {!editing && planState.status === "paused" && onResumePlan !== undefined ? (
            <button
              type="button"
              className="ja-plan-button is-primary"
              disabled={isBusy}
              onClick={() => void onResumePlan()}
            >
              <RotateCcw aria-hidden="true" />
              继续
            </button>
          ) : null}
          {!editing && planState.status === "executing" && onPausePlan !== undefined ? (
            <button
              type="button"
              className="ja-plan-icon-button"
              aria-label="暂停计划"
              title="暂停计划"
              disabled={isBusy}
              onClick={() => void onPausePlan()}
            >
              <CirclePause aria-hidden="true" />
            </button>
          ) : null}
          {!editing &&
          (planState.status === "executing" || planState.status === "paused") &&
          onStopPlan !== undefined ? (
            <button
              type="button"
              className="ja-plan-icon-button is-danger"
              aria-label="停止计划"
              title="停止计划"
              disabled={isBusy}
              onClick={() => void onStopPlan()}
            >
              <Square aria-hidden="true" />
            </button>
          ) : null}
        </div>
      </header>

      {error === undefined ? null : (
        <div className="ja-plan-alert" role="alert">
          <AlertCircle aria-hidden="true" />
          <span>{error}</span>
          {onRetry === undefined ? null : (
            <button type="button" onClick={onRetry}>
              重试
            </button>
          )}
        </div>
      )}

      {editing ? (
        <PlanDraftEditor
          draft={selectedDraft}
          busy={isBusy}
          saveState={draftSaveState}
          onChange={(next) => {
            setDraft(next);
            draftRef.current = next;
            setDraftDirty(true);
            draftDirtyRef.current = true;
            draftChangedRef.current = true;
            localEditRevisionRef.current += 1;
            setDraftSaveState("idle");
          }}
          onCancel={cancelLocalEdit}
          onSave={
            onSaveDraft === undefined
              ? undefined
              : async () => {
                  const scopeKey = currentPlanKeyRef.current;
                  if (scopeKey === undefined) return false;
                  return flushLatestDraft(scopeKey);
                }
          }
          onDiscard={
            resolvedPlanModel.draft === null || onDiscardDraft === undefined
              ? undefined
              : async () => {
                  if ((await onDiscardDraft()) === false) return;
                  const scopeKey = `${resolvedPlanModel.plan.ownerThreadId}:${resolvedPlanModel.plan.planId}`;
                  localEditRevisionRef.current += 1;
                  invalidateQueuedDraftSave(scopeKey);
                  clearRetainedDraft(scopeKey);
                  const nextDraft = editableDraft(resolvedPlanModel);
                  setDraft(nextDraft);
                  draftRef.current = nextDraft;
                  setEditing(false);
                  draftDirtyRef.current = false;
                  setDraftDirty(false);
                  const shouldResume = resumeAfterEditRef.current && !draftChangedRef.current;
                  draftChangedRef.current = false;
                  setDraftDirty(false);
                  if (shouldResume && onCancelEdit !== undefined) {
                    await onCancelEdit();
                    resumeAfterEditRef.current = false;
                  }
                }
          }
          onFinalize={(onFinalizePlan ?? onPropose) === undefined ? undefined : finishEditing}
        />
      ) : plan === null ? (
        <div className="ja-plan-state" role="status">
          <Circle aria-hidden="true" />
          <span>计划尚未提出</span>
        </div>
      ) : (
        <PlanRevisionView
          plan={plan}
          evidence={evidence}
          currentStepId={currentStep?.stepId}
          evaluation={evaluation}
        />
      )}

      {revisions.length < 2 || plan === null ? null : (
        <section
          className="ja-plan-section ja-plan-revisions"
          aria-labelledby="ja-plan-revisions-title"
        >
          <div className="ja-plan-section__heading">
            <GitCompare aria-hidden="true" />
            <h3 id="ja-plan-revisions-title">版本比较</h3>
          </div>
          <label>
            <span>与当前版本比较</span>
            <span className="ja-plan-select-shell">
              <select
                value={compareRevisionId ?? ""}
                onChange={(event) => setCompareRevisionId(event.currentTarget.value || undefined)}
              >
                <option value="">选择版本</option>
                {revisions
                  .filter((revision) => revision.planRevisionId !== plan.planRevisionId)
                  .map((revision) => (
                    <option key={revision.planRevisionId} value={revision.planRevisionId}>
                      版本 {revision.revisionNumber}
                    </option>
                  ))}
              </select>
              <ChevronDown aria-hidden="true" />
            </span>
          </label>
          {compareRevision === undefined ? null : (
            <div className="ja-plan-diff" role="status">
              {changes.length === 0 ? "结构化内容没有变化" : `已变化：${changes.join("、")}`}
            </div>
          )}
        </section>
      )}
    </main>
  );
}

/** 编辑器始终提交完整结构，不以 Markdown、索引或临时完成标记作为权威来源。 */
function PlanDraftEditor({
  draft,
  busy,
  saveState,
  onChange,
  onCancel,
  onSave,
  onDiscard,
  onFinalize,
}: {
  draft: PlanDraft;
  busy: boolean;
  saveState: "idle" | "saving" | "saved" | "error";
  onChange: (draft: PlanDraft) => void;
  onCancel: () => void;
  onSave?: () => void | boolean | Promise<void | boolean>;
  onDiscard?: () => void | boolean | Promise<void | boolean>;
  onFinalize?: () => void | boolean | Promise<void | boolean>;
}): ReactElement {
  /** 更新 draft 保留其服务端身份和 base revision，避免编辑过程制造新版本。 */
  const patch = (next: Partial<PlanDraft>): void => onChange({ ...draft, ...next });
  const valid =
    draft.objective.trim() !== "" && draft.steps.length > 0 && draft.acceptanceCriteria.length > 0;
  return (
    <section className="ja-plan-editor" aria-label="编辑计划草稿">
      <label className="ja-plan-field is-wide">
        <span>目标</span>
        <textarea
          aria-label="目标"
          value={draft.objective}
          onChange={(event) => patch({ objective: event.currentTarget.value })}
        />
      </label>
      <div className="ja-plan-editor__grid">
        <ListField label="范围" value={draft.scope} onChange={(scope) => patch({ scope })} />
        <ListField
          label="非目标"
          value={draft.nonGoals}
          onChange={(nonGoals) => patch({ nonGoals })}
        />
        <ListField
          label="约束"
          value={draft.constraints}
          onChange={(constraints) => patch({ constraints })}
        />
        <ListField label="风险" value={draft.risks} onChange={(risks) => patch({ risks })} />
      </div>
      <EditableSteps steps={draft.steps} onChange={(steps) => patch({ steps })} />
      <EditableCriteria
        criteria={draft.acceptanceCriteria}
        onChange={(acceptanceCriteria) => patch({ acceptanceCriteria })}
      />
      <ListField
        label="验证策略"
        value={draft.verificationStrategy}
        onChange={(verificationStrategy) => patch({ verificationStrategy })}
      />
      <footer className="ja-plan-editor__actions">
        <span className="ja-plan-save-state" role="status" data-state={saveState}>
          {saveState === "saving"
            ? "正在保存…"
            : saveState === "saved"
              ? "已保存"
              : saveState === "error"
                ? "版本已变化，保留本地修改"
                : "自动保存已开启"}
        </span>
        {onDiscard === undefined ? (
          <button type="button" className="ja-plan-button is-secondary" onClick={onCancel}>
            <X aria-hidden="true" />
            取消
          </button>
        ) : (
          <button
            type="button"
            className="ja-plan-button is-danger"
            disabled={busy}
            onClick={() => void onDiscard()}
          >
            <Trash2 aria-hidden="true" />
            放弃草稿
          </button>
        )}
        {onSave === undefined ? null : (
          <button
            type="button"
            className="ja-plan-button is-secondary"
            disabled={busy || !valid}
            onClick={() => void onSave()}
          >
            <Save aria-hidden="true" />
            保存草稿
          </button>
        )}
        {onFinalize === undefined ? null : (
          <button
            type="button"
            className="ja-plan-button is-primary"
            disabled={busy || !valid}
            onClick={() => void onFinalize()}
          >
            <Check aria-hidden="true" />
            完成编辑
          </button>
        )}
      </footer>
    </section>
  );
}

/** 多值字段仍用结构化数组提交；换行只是编辑控件，不是持久 Markdown 协议。 */
function ListField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: readonly string[];
  onChange: (value: string[]) => void;
}): ReactElement {
  return (
    <label className="ja-plan-field">
      <span>{label}</span>
      <textarea
        aria-label={label}
        value={value.join("\n")}
        onChange={(event) => onChange(lines(event.currentTarget.value))}
      />
    </label>
  );
}

/** 步骤依赖按稳定 ID 编辑，删除步骤同时清理其余步骤的悬空依赖。 */
function EditableSteps({
  steps,
  onChange,
}: {
  steps: readonly PlanDraftStep[];
  onChange: (steps: PlanDraftStep[]) => void;
}): ReactElement {
  const update = (index: number, next: Partial<PlanDraftStep>): void =>
    onChange(steps.map((step, position) => (position === index ? { ...step, ...next } : step)));
  const remove = (stepId: string): void =>
    onChange(
      steps
        .filter((step) => step.stepId !== stepId)
        .map((step) => ({
          ...step,
          dependencyStepIds: step.dependencyStepIds.filter((id) => id !== stepId),
        })),
    );
  return (
    <section className="ja-plan-section" aria-labelledby="ja-plan-edit-steps">
      <div className="ja-plan-section__heading">
        <h3 id="ja-plan-edit-steps">实施步骤</h3>
        <button
          type="button"
          className="ja-plan-icon-button"
          aria-label="添加步骤"
          title="添加步骤"
          onClick={() =>
            onChange([
              ...steps,
              {
                stepId: localId("step"),
                title: "",
                description: "",
                required: true,
                dependencyStepIds: [],
              },
            ])
          }
        >
          <Plus aria-hidden="true" />
        </button>
      </div>
      <ol className="ja-plan-edit-list">
        {steps.map((step, index) => (
          <li key={step.stepId}>
            <div className="ja-plan-edit-row">
              <input
                aria-label={`步骤 ${index + 1} 标题`}
                value={step.title}
                onChange={(event) => update(index, { title: event.currentTarget.value })}
              />
              <button
                type="button"
                className="ja-plan-icon-button"
                aria-label={`删除步骤 ${index + 1}`}
                title="删除步骤"
                onClick={() => remove(step.stepId)}
              >
                <Trash2 aria-hidden="true" />
              </button>
            </div>
            <textarea
              aria-label={`步骤 ${index + 1} 说明`}
              value={step.description}
              onChange={(event) => update(index, { description: event.currentTarget.value })}
            />
            <label className="ja-plan-checkbox">
              <input
                type="checkbox"
                checked={step.required}
                onChange={(event) => update(index, { required: event.currentTarget.checked })}
              />
              <span>必要步骤</span>
            </label>
            <fieldset>
              <legend>依赖</legend>
              {steps
                .filter((candidate) => candidate.stepId !== step.stepId)
                .map((candidate) => (
                  <label key={candidate.stepId} className="ja-plan-checkbox">
                    <input
                      type="checkbox"
                      checked={step.dependencyStepIds.includes(candidate.stepId)}
                      onChange={(event) =>
                        update(index, {
                          dependencyStepIds: event.currentTarget.checked
                            ? [...step.dependencyStepIds, candidate.stepId]
                            : step.dependencyStepIds.filter((id) => id !== candidate.stepId),
                        })
                      }
                    />
                    <span>{candidate.title || "未命名步骤"}</span>
                  </label>
                ))}
            </fieldset>
          </li>
        ))}
      </ol>
    </section>
  );
}

/** 验收条件保留稳定 ID 与 required 语义，文本变化不会使既有证据错误重绑。 */
function EditableCriteria({
  criteria,
  onChange,
}: {
  criteria: readonly PlanDraftCriterion[];
  onChange: (criteria: PlanDraftCriterion[]) => void;
}): ReactElement {
  const update = (index: number, next: Partial<PlanDraftCriterion>): void =>
    onChange(
      criteria.map((criterion, position) =>
        position === index ? { ...criterion, ...next } : criterion,
      ),
    );
  return (
    <section className="ja-plan-section" aria-labelledby="ja-plan-edit-criteria">
      <div className="ja-plan-section__heading">
        <h3 id="ja-plan-edit-criteria">验收条件</h3>
        <button
          type="button"
          className="ja-plan-icon-button"
          aria-label="添加验收条件"
          title="添加验收条件"
          onClick={() =>
            onChange([
              ...criteria,
              { criterionId: localId("criterion"), description: "", required: true },
            ])
          }
        >
          <Plus aria-hidden="true" />
        </button>
      </div>
      <ol className="ja-plan-edit-list">
        {criteria.map((criterion, index) => (
          <li key={criterion.criterionId}>
            <div className="ja-plan-edit-row">
              <textarea
                aria-label={`验收条件 ${index + 1}`}
                value={criterion.description}
                onChange={(event) => update(index, { description: event.currentTarget.value })}
              />
              <button
                type="button"
                className="ja-plan-icon-button"
                aria-label={`删除验收条件 ${index + 1}`}
                title="删除验收条件"
                onClick={() => onChange(criteria.filter((_, position) => position !== index))}
              >
                <Trash2 aria-hidden="true" />
              </button>
            </div>
            <label className="ja-plan-checkbox">
              <input
                type="checkbox"
                checked={criterion.required}
                onChange={(event) => update(index, { required: event.currentTarget.checked })}
              />
              <span>必要条件</span>
            </label>
          </li>
        ))}
      </ol>
    </section>
  );
}

/**
 * 未关联 Plan 的 Goal 仍有独立 definition 与可信运行证据；详情视图直接展示该聚合，
 * 不伪造 Plan identity，也不把“没有计划”当作错误状态。
 */
function GoalOnlyView({
  model,
  evidence,
}: {
  model: GoalReadModel;
  evidence: readonly AcceptanceEvidence[];
}): ReactElement {
  const currentEvidence = evidence.filter(
    (item) =>
      item.goalDefinitionRevision === model.goal.goalDefinitionRevision &&
      item.planRevisionId === null,
  );
  const unboundEvidence = currentEvidence.filter((item) => item.criterionId === null);
  return (
    <main
      className="ja-plan-workbench"
      data-goal-ui="plan-workbench"
      data-goal-id={model.goal.goalId}
      data-goal-phase={model.goal.phase}
    >
      <header className="ja-plan-header">
        <div className="ja-plan-header__copy">
          <span>{goalPhaseLabel(model.goal.phase)}</span>
          <h2 title={model.goal.objective}>{model.goal.objective}</h2>
          <small>目标定义版本 {model.goal.goalDefinitionRevision}</small>
        </div>
      </header>
      <section className="ja-plan-section">
        <h3>验收与证据</h3>
        {model.goal.acceptanceCriteria.length === 0 && unboundEvidence.length === 0 ? (
          <p>暂无有效证据</p>
        ) : (
          <ol className="ja-plan-criteria-list">
            {model.goal.acceptanceCriteria.map((criterion) => {
              const attached = currentEvidence.filter(
                (item) => item.criterionId === criterion.criterionId,
              );
              return (
                <li key={criterion.criterionId}>
                  <div>
                    <strong>{criterion.description}</strong>
                    <span>{attached.length === 0 ? "待验证" : `已记录 ${attached.length} 条`}</span>
                  </div>
                  <EvidenceList items={attached} />
                </li>
              );
            })}
            {unboundEvidence.length === 0 ? null : (
              <li>
                <div>
                  <strong>目标运行证据</strong>
                  <span>已记录 {unboundEvidence.length} 条</span>
                </div>
                <EvidenceList items={unboundEvidence} />
              </li>
            )}
          </ol>
        )}
      </section>
    </main>
  );
}

/** 空证据不生成空列表；摘要与 digest 只展示 adapter 已校验的服务端事实。 */
function EvidenceList({ items }: { items: readonly AcceptanceEvidence[] }): ReactElement | null {
  if (items.length === 0) return null;
  return (
    <ul>
      {items.map((item) => (
        <li key={item.evidenceId}>
          <Target aria-hidden="true" />
          <span>{item.summary}</span>
          <small>{new Date(item.recordedAt).toLocaleString()}</small>
        </li>
      ))}
    </ul>
  );
}

/** 冻结版本只读展示，步骤、依赖、验收和证据保持可扫描而不嵌套装饰卡片。 */
function PlanRevisionView({
  plan,
  evidence,
  currentStepId,
  evaluation,
}: {
  plan: PlanRevision;
  evidence: readonly AcceptanceEvidence[];
  currentStepId?: string;
  evaluation: GoalReadModel["evaluation"];
}): ReactElement {
  return (
    <div className="ja-plan-readonly">
      <section className="ja-plan-section">
        <h3>范围与约束</h3>
        <DefinitionList label="范围" items={plan.scope} />
        <DefinitionList label="非目标" items={plan.nonGoals} />
        <DefinitionList label="约束" items={plan.constraints} />
      </section>
      <section className="ja-plan-section">
        <h3>实施步骤</h3>
        <ol className="ja-plan-step-list">
          {plan.steps.map((step, index) => (
            <li
              key={step.stepId}
              data-status={step.status}
              data-current={step.stepId === currentStepId || undefined}
            >
              <span className="ja-plan-step-list__index">
                {step.status === "succeeded" ? <Check aria-hidden="true" /> : index + 1}
              </span>
              <div>
                <strong>{step.title}</strong>
                <p>{step.description}</p>
                {step.dependencyStepIds.length === 0 ? null : (
                  <small>
                    依赖：
                    {step.dependencyStepIds
                      .map(
                        (id) =>
                          plan.steps.find((candidate) => candidate.stepId === id)?.title ?? id,
                      )
                      .join("、")}
                  </small>
                )}
                {step.blockingReason === null ? null : <em>{step.blockingReason}</em>}
              </div>
              <span className="ja-plan-status-label">{planStepStatusLabel(step.status)}</span>
            </li>
          ))}
        </ol>
      </section>
      <section className="ja-plan-section">
        <h3>验收与证据</h3>
        <ol className="ja-plan-criteria-list">
          {plan.acceptanceCriteria.map((criterion) => {
            const attached = evidence.filter((item) => item.criterionId === criterion.criterionId);
            return (
              <li key={criterion.criterionId}>
                <div>
                  <strong>{criterion.description}</strong>
                  <span data-status={criterion.status}>
                    {criterion.status === "met"
                      ? "已满足"
                      : criterion.status === "not_met"
                        ? "未满足"
                        : criterion.status === "inconclusive"
                          ? "待确认"
                          : "待验证"}
                  </span>
                </div>
                {attached.length === 0 ? (
                  <small>暂无有效证据</small>
                ) : (
                  <ul>
                    {attached.map((item) => (
                      <li key={item.evidenceId}>
                        <span>{item.summary}</span>
                        <small>{new Date(item.recordedAt).toLocaleString()}</small>
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            );
          })}
        </ol>
        {evaluation === null ? null : (
          <div className="ja-plan-evaluation" data-verdict={evaluation.verdict}>
            <ShieldCheck aria-hidden="true" />
            <div>
              <strong>
                {evaluation.verdict === "met"
                  ? "独立验收已通过"
                  : evaluation.verdict === "not_met"
                    ? "独立验收未通过"
                    : "独立验收待确认"}
              </strong>
              <p>{evaluation.summary}</p>
            </div>
          </div>
        )}
      </section>
      <section className="ja-plan-section">
        <h3>风险与验证</h3>
        <DefinitionList label="风险" items={plan.risks} />
        <DefinitionList label="验证策略" items={plan.verificationStrategy} />
      </section>
    </div>
  );
}

/** 空集合显式显示“无”，防止只读审查把缺失数据误认为折叠状态。 */
function DefinitionList({
  label,
  items,
}: {
  label: string;
  items: readonly string[];
}): ReactElement {
  return (
    <dl className="ja-plan-definition">
      <dt>{label}</dt>
      <dd>
        {items.length === 0 ? (
          "无"
        ) : (
          <ul>
            {items.map((item, index) => (
              <li key={`${index}-${item}`}>{item}</li>
            ))}
          </ul>
        )}
      </dd>
    </dl>
  );
}
