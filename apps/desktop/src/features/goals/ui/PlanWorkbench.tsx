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
  ShieldCheck,
  Target,
  Trash2,
  X,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState, type ReactElement } from "react";
import type { GoalMutationAction } from "../application/ports";
import {
  goalPhaseLabel,
  planStatusLabel,
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
  readonly onApprove?: () => void | boolean | Promise<void | boolean>;
  readonly onExecute?: () => void | boolean | Promise<void | boolean>;
  readonly onAttachPlan?: () => void | boolean | Promise<void | boolean>;
  readonly onDetachPlan?: () => void | boolean | Promise<void | boolean>;
  readonly onReject?: () => void | boolean | Promise<void | boolean>;
  readonly onPause?: () => void | boolean | Promise<void | boolean>;
  readonly onResume?: () => void | boolean | Promise<void | boolean>;
  readonly onBeginEdit?: () => void | boolean | Promise<void | boolean>;
  readonly onCancelEdit?: () => void | boolean | Promise<void | boolean>;
  readonly onContinue?: () => void | boolean | Promise<void | boolean>;
  readonly onRespondInput?: (response: string) => void | boolean | Promise<void | boolean>;
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

/** Diff 只比较结构化字段，不把计划回退为 Markdown 文本差异。 */
export function revisionDiff(current: PlanRevision, prior: PlanRevision): readonly string[] {
  const changes: string[] = [];
  if (current.objective !== prior.objective) changes.push("目标");
  if (JSON.stringify(current.scope) !== JSON.stringify(prior.scope)) changes.push("范围");
  if (JSON.stringify(current.nonGoals) !== JSON.stringify(prior.nonGoals)) changes.push("非目标");
  if (JSON.stringify(current.constraints) !== JSON.stringify(prior.constraints))
    changes.push("约束");
  if (JSON.stringify(current.steps) !== JSON.stringify(prior.steps)) changes.push("步骤与依赖");
  if (JSON.stringify(current.acceptanceCriteria) !== JSON.stringify(prior.acceptanceCriteria))
    changes.push("验收条件");
  if (JSON.stringify(current.risks) !== JSON.stringify(prior.risks)) changes.push("风险");
  if (JSON.stringify(current.verificationStrategy) !== JSON.stringify(prior.verificationStrategy))
    changes.push("验证策略");
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
  onApprove,
  onExecute,
  onAttachPlan,
  onDetachPlan,
  onReject,
  onPause,
  onResume,
  onBeginEdit,
  onCancelEdit,
  onContinue,
  onRespondInput,
}: PlanWorkbenchProps): ReactElement {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<PlanDraft>();
  const [compareRevisionId, setCompareRevisionId] = useState<string>();
  const [inputResponse, setInputResponse] = useState("");
  const [startingEdit, setStartingEdit] = useState(false);
  const editButtonRef = useRef<HTMLButtonElement>(null);
  const resumeAfterEditRef = useRef(false);
  const renderedPlanIdRef = useRef<string | undefined>(undefined);
  const resolvedPlanModel = useMemo(
    () => planModel ?? (model === undefined ? undefined : linkedPlanView(model)),
    [model, planModel],
  );

  /**
   * 同一 Plan 的 read/observe 可能在用户点击编辑后迟到；服务端尚无 draft 时保留本地编辑，
   * 只有切换 Plan 或收到权威 draft 才重建表单，避免把刚出现的输入框竞态卸载。
   */
  useEffect(() => {
    if (resolvedPlanModel === undefined) return;
    const planChanged = renderedPlanIdRef.current !== resolvedPlanModel.plan.planId;
    renderedPlanIdRef.current = resolvedPlanModel.plan.planId;
    if (planChanged || resolvedPlanModel.draft !== null) {
      setDraft(editableDraft(resolvedPlanModel));
      setEditing(resolvedPlanModel.draft !== null);
      return;
    }
    setDraft((current) => current ?? editableDraft(resolvedPlanModel));
  }, [resolvedPlanModel]);

  useEffect(() => {
    resumeAfterEditRef.current = false;
  }, [resolvedPlanModel?.plan.planId]);

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
    setDraft(editableDraft(resolvedPlanModel));
    setEditing(false);
    if (resumeAfterEditRef.current && onCancelEdit !== undefined) {
      await onCancelEdit();
      resumeAfterEditRef.current = false;
    }
    window.requestAnimationFrame(() => editButtonRef.current?.focus());
  };

  /** 已批准版本先等待服务端暂停 ACK，再开放本地编辑，避免执行与改版并发。 */
  const beginEdit = async (): Promise<void> => {
    if (resolvedPlanModel === undefined || startingEdit) return;
    // 用户可能在首次同步 effect 执行前立即点击；先锁定当前 identity，避免该 effect 反向关闭编辑态。
    renderedPlanIdRef.current = resolvedPlanModel.plan.planId;
    setStartingEdit(true);
    try {
      if (onBeginEdit !== undefined) {
        if ((await onBeginEdit()) === false) return;
        resumeAfterEditRef.current = true;
      }
      setDraft(editableDraft(resolvedPlanModel));
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
  const inputRequest = linkedToGoal ? (model?.inputRequest ?? null) : null;
  const evaluation = linkedToGoal ? (model?.evaluation ?? null) : null;
  const isBusy = busyAction !== undefined;
  const currentStep = plan?.steps.find(
    (step) => step.stepId === (linkedToGoal ? goal?.currentStepId : null),
  );
  const selectedDraft = draft ?? editableDraft(resolvedPlanModel);
  const primaryAction =
    linkedToGoal && goal !== undefined ? goalPrimaryAction(goal, evaluation) : undefined;

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
          <small>
            {plan === null
              ? "尚无已确认版本"
              : `版本 ${plan.revisionNumber} · ${plan.planHash.slice(0, 10)}`}
          </small>
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
          planState.status === "awaiting_approval" &&
          plan !== null &&
          onApprove !== undefined ? (
            <button
              type="button"
              className="ja-plan-button is-primary"
              disabled={isBusy}
              onClick={() => void onApprove()}
            >
              <ShieldCheck aria-hidden="true" />
              批准
            </button>
          ) : null}
          {!editing &&
          planState.status === "approved" &&
          plan !== null &&
          (onAttachPlan !== undefined || onExecute !== undefined) ? (
            <button
              type="button"
              className="ja-plan-button is-primary"
              disabled={isBusy}
              onClick={() => void (onAttachPlan ?? onExecute)?.()}
            >
              <ShieldCheck aria-hidden="true" />
              {onAttachPlan !== undefined ? "用于当前目标" : "执行计划"}
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

      {inputRequest === null ? null : (
        <section className="ja-plan-input-request" aria-labelledby="ja-plan-input-title">
          <h3 id="ja-plan-input-title">需要你的输入</h3>
          <p>{inputRequest.prompt}</p>
          {onRespondInput === undefined ? null : (
            <form
              onSubmit={(event) => {
                event.preventDefault();
                const response = inputResponse.trim();
                if (response !== "") void onRespondInput(response);
              }}
            >
              <textarea
                aria-label="目标所需输入"
                value={inputResponse}
                onChange={(event) => setInputResponse(event.currentTarget.value)}
              />
              <button
                type="submit"
                className="ja-plan-button is-primary"
                disabled={isBusy || inputResponse.trim() === ""}
              >
                提交输入
              </button>
            </form>
          )}
        </section>
      )}

      {editing ? (
        <PlanDraftEditor
          draft={selectedDraft}
          busy={isBusy}
          onChange={setDraft}
          onCancel={cancelLocalEdit}
          onSave={
            onSaveDraft === undefined
              ? undefined
              : async () => {
                  await onSaveDraft({ ...selectedDraft, updatedAt: new Date().toISOString() });
                }
          }
          onDiscard={
            resolvedPlanModel.draft === null || onDiscardDraft === undefined
              ? undefined
              : async () => {
                  if ((await onDiscardDraft()) === false) return;
                  setEditing(false);
                  if (resumeAfterEditRef.current && onCancelEdit !== undefined) {
                    await onCancelEdit();
                    resumeAfterEditRef.current = false;
                  }
                }
          }
          onPropose={
            resolvedPlanModel.draft === null || onPropose === undefined
              ? undefined
              : async () => {
                  if ((await onPropose()) === false) return;
                  setEditing(false);
                  resumeAfterEditRef.current = false;
                }
          }
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

      {!editing &&
      planState.status === "awaiting_approval" &&
      plan !== null &&
      onReject !== undefined ? (
        <footer className="ja-plan-footer">
          <button
            type="button"
            className="ja-plan-button is-danger"
            disabled={isBusy}
            onClick={() => void onReject()}
          >
            拒绝此版本
          </button>
        </footer>
      ) : null}
    </main>
  );
}

/** 编辑器始终提交完整结构，不以 Markdown、索引或临时完成标记作为权威来源。 */
function PlanDraftEditor({
  draft,
  busy,
  onChange,
  onCancel,
  onSave,
  onDiscard,
  onPropose,
}: {
  draft: PlanDraft;
  busy: boolean;
  onChange: (draft: PlanDraft) => void;
  onCancel: () => void;
  onSave?: () => void | boolean | Promise<void | boolean>;
  onDiscard?: () => void | boolean | Promise<void | boolean>;
  onPropose?: () => void | boolean | Promise<void | boolean>;
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
        {onPropose === undefined ? null : (
          <button
            type="button"
            className="ja-plan-button is-primary"
            disabled={busy || !valid}
            onClick={() => void onPropose()}
          >
            <ShieldCheck aria-hidden="true" />
            提交审批
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
          <code title={item.digest}>{item.digest.slice(0, 10)}</code>
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
                        <code title={item.digest}>{item.digest.slice(0, 10)}</code>
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
