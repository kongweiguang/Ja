// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Bot, ChevronDown, CircleAlert, LoaderCircle } from "lucide-react";
import { useEffect, useMemo, useState, type ReactElement } from "react";
import { useShallow } from "zustand/react/shallow";
import {
  ChatTimeline,
  Composer,
  InteractionCard,
  resolveContextUsage,
  selectApprovalClosedAt,
  selectApprovalDecisions,
  selectApprovals,
  selectGoalActivitiesForOwner,
  selectItemsForThread,
  useTimelineStore,
  type ComposerSlashCommand,
  type ComposerNativeDropEvent,
  type ComposerWorkspaceSearchResult,
  type ConversationAttachment,
  type ConversationAttachmentPort,
  type ConversationContextReference,
  type ConversationInteractionController,
  type InteractionController,
  type TimelineGoalActivity,
} from "@/features/conversation";
import {
  ComposerGoalStatus,
  GoalActivityCard,
  GoalStatusBar,
  PlanTimelineBlock,
  PlanWorkbench,
  type GoalController,
} from "@/features/goals";
import { Button, ErrorState } from "@/shared/ui/primitives";
import type { WorkbenchTaskTab } from "@/features/workbench";
import type { TaskController } from "../application/useTaskController";
import { propagatingTaskDescendantCount, taskStateLabel } from "../domain/taskModel";
import "./tasks.css";

export interface TaskComposerSkillSuggestion {
  readonly skillId: string;
  readonly name: string;
  readonly description: string;
  readonly scope: "builtin" | "user" | "ja" | "project";
}

export interface TaskComposerEnvironment {
  readonly workspaceId: string;
  readonly runtimeGeneration?: number;
  readonly nativeDropEvent?: ComposerNativeDropEvent;
  readonly dropZoneRef?: (element: HTMLFormElement | null) => void;
  readonly skills?: readonly TaskComposerSkillSuggestion[];
  readonly attachmentPort?: ConversationAttachmentPort;
  readonly slashCommands?: readonly ComposerSlashCommand[];
  readonly onSearchWorkspacePaths?: (query: string) => Promise<ComposerWorkspaceSearchResult>;
  readonly onOpenAttachmentPreview?: (
    attachment: ConversationAttachment,
    source: HTMLButtonElement,
  ) => void;
  readonly onOpenQueuedAttachmentPreview?: (
    attachment: ConversationAttachment,
    source: HTMLButtonElement,
  ) => void;
  readonly onRestoreDefaults?: () => void | Promise<void>;
  readonly onOpenWorkspaceReference?: (
    reference: Extract<ConversationContextReference, { type: "workspace_reference" }>,
    source: HTMLButtonElement,
  ) => void;
}

export interface TaskTranscriptActions {
  readonly onOpenLink?: (url: string) => void | Promise<void>;
  readonly onCopyText?: (text: string) => Promise<void>;
  readonly onReadToolArtifact?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    artifactId: string;
  }) => Promise<string>;
  readonly onOpenAttachmentPreview?: (
    attachment: {
      attachmentId: string;
      displayName: string;
      mediaKind: "image" | "text";
      threadId: string;
    },
    source: HTMLButtonElement,
  ) => void;
}

/** 读失败保留原会话和草稿；重试只重读当前 Task，不重新发送任何内容。 */
function DetailReadAlert({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}): ReactElement {
  return (
    <div className="ja-task-read-alert" role="alert">
      <CircleAlert aria-hidden="true" />
      <span>{message}</span>
      <Button variant="ghost" size="sm" onClick={onRetry}>
        重试
      </Button>
    </div>
  );
}

/**
 * 侧聊拥有真实 Thread，和主任务使用相同的交互 controller、Timeline、Composer 与 Plan/Goal
 * 组件。视图不再创建精简版草稿或解析历史格式；Subagent 保持只读正文和显式控制入口。
 */
export function TaskDetailPanel({
  tab,
  controller,
  composerEnvironment: environment,
  transcriptActions,
  conversation,
  goal,
  clarification,
  planDetailsOpen = false,
  onPlanDetailsChange,
  focusRequest,
}: {
  tab: WorkbenchTaskTab;
  controller: TaskController;
  composerEnvironment?: TaskComposerEnvironment;
  transcriptActions?: TaskTranscriptActions;
  conversation?: ConversationInteractionController;
  goal?: GoalController;
  clarification?: InteractionController;
  planDetailsOpen?: boolean;
  onPlanDetailsChange?: (open: boolean) => void;
  /** 空侧聊创建完成后由 Host 发出一次性焦点 token，避免查询全局 DOM 抢走主 Composer 焦点。 */
  focusRequest?: number;
}): ReactElement {
  const threadId = tab.taskThreadId ?? "";
  const task =
    controller.detail?.task.taskThreadId === threadId
      ? controller.detail.task
      : controller.tasks.find((candidate) => candidate.taskThreadId === threadId);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [controlBusy, setControlBusy] = useState(false);
  const [controlError, setControlError] = useState<string>();
  /** Subagent 的递归取消保持显式确认；失败不会把 Task 状态乐观改成终态。 */
  const controlSubagent = async (resume: boolean): Promise<void> => {
    if (task === undefined || controlBusy) return;
    setControlBusy(true);
    setControlError(undefined);
    try {
      if (resume) await controller.resume(task);
      else await controller.cancel(task);
      setConfirmCancel(false);
    } catch {
      setControlError(resume ? "任务恢复失败，请重试。" : "取消请求未完成，请重试。");
    } finally {
      setControlBusy(false);
    }
  };
  const items = useTimelineStore(useShallow(selectItemsForThread(threadId)));
  const turns = useTimelineStore(
    useShallow((state) => Object.values(state.turns).filter((turn) => turn.threadId === threadId)),
  );
  const approvals = useTimelineStore(
    useShallow((state) =>
      selectApprovals(state).filter((approval) => approval.threadId === threadId),
    ),
  );
  const approvalDecisions = useTimelineStore(useShallow(selectApprovalDecisions));
  const approvalClosedAt = useTimelineStore(useShallow(selectApprovalClosedAt));
  const persistedGoalActivities = useTimelineStore(selectGoalActivitiesForOwner(threadId));
  /** 与主会话一样以 mutation ACK 补齐终态卡片，下一次持久快照再按 Goal identity 去重接管。 */
  const goalActivities = useMemo<readonly TimelineGoalActivity[]>(() => {
    const current = goal?.model?.goal;
    if (
      current === undefined ||
      current.ownerThreadId !== threadId ||
      (current.status !== "achieved" && current.status !== "stopped") ||
      persistedGoalActivities.some((activity) => activity.goalId === current.goalId)
    )
      return persistedGoalActivities;
    return [
      ...persistedGoalActivities,
      {
        goalId: current.goalId,
        objective: current.objective,
        status: current.status,
        goalRevision: current.revision,
        eventSequence: goal?.model?.eventSequence ?? current.revision,
        occurredAt: current.updatedAt,
      },
    ];
  }, [goal?.model, persistedGoalActivities, threadId]);
  const usage = useTimelineStore((state) => state.contextUsageByThread[threadId]);
  const compaction = useTimelineStore((state) => state.contextCompactionByThread[threadId]);
  const contextUsage = useMemo(
    () => resolveContextUsage({ usage, compaction }),
    [usage, compaction],
  );
  const context =
    controller.detail?.task.taskThreadId === threadId ? controller.detail.contextSeed : undefined;
  const closing = controller.closingTaskThreadId === threadId;
  const currentStepTitle = goal?.model?.plan?.steps.find(
    (step) => step.stepId === goal.model?.goal.currentStepId,
  )?.title;
  const latestTerminalTurnId = turns
    .filter((turn) => ["completed", "failed", "cancelled"].includes(turn.status))
    .sort((left, right) => (left.completedAt ?? "").localeCompare(right.completedAt ?? ""))
    .at(-1)?.turnId;
  const currentPlanId = goal?.planModel?.plan.planId;
  const refreshGoal = goal?.refresh;
  /** 独立 Plan 不一定产生 Goal 事件；与主会话一样在 Turn 终态重读一次权威 Plan。 */
  useEffect(() => {
    if (currentPlanId !== undefined && latestTerminalTurnId !== undefined) void refreshGoal?.();
  }, [currentPlanId, latestTerminalTurnId, refreshGoal]);
  const externalRows = goalActivities.map((activity) => ({
    rowId: `goal-terminal:${activity.goalId}`,
    occurredAt: activity.occurredAt,
    revision: `${activity.goalRevision}:${activity.eventSequence}`,
    content: <GoalActivityCard activity={activity} />,
  }));
  if (goal?.planModel !== undefined)
    externalRows.push({
      rowId: `plan:${goal.planModel.plan.planId}`,
      occurredAt: goal.planModel.plan.updatedAt,
      revision: String(goal.planModel.plan.revision),
      content: (
        <PlanTimelineBlock
          model={goal.model}
          planModel={goal.planModel}
          busy={goal.busyAction !== undefined}
          onOpenDetails={() => onPlanDetailsChange?.(true)}
          onExecute={goal.execute}
        />
      ),
    });

  if (controller.detailError !== undefined && task === undefined)
    return (
      <ErrorState
        title="任务详情暂不可用"
        message={controller.detailError}
        onRetry={() => void controller.refreshDetail()}
      />
    );
  return (
    <section className="ja-task-detail" aria-label={task?.taskName ?? tab.label}>
      {tab.taskKind === "subagent" ? (
        <header className="ja-task-detail-header">
          <div>
            <span>
              <Bot aria-hidden="true" />
              Subagent
            </span>
            <h2>{task?.taskName ?? tab.label}</h2>
            {task === undefined ? null : <small>{taskStateLabel(task.state)}</small>}
          </div>
          {task?.state === "suspended" ? (
            <Button size="sm" disabled={controlBusy} onClick={() => void controlSubagent(true)}>
              继续运行
            </Button>
          ) : task !== undefined &&
            ["queued", "running", "waiting_approval"].includes(task.state) ? (
            confirmCancel ? (
              <div className="ja-task-cancel-confirm" role="group" aria-label="确认取消任务">
                <span>
                  将同时取消 {propagatingTaskDescendantCount(controller.tasks, task)} 个未结束后代
                </span>
                <Button
                  variant="danger"
                  size="sm"
                  disabled={controlBusy}
                  onClick={() => void controlSubagent(false)}
                >
                  确认取消
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={controlBusy}
                  onClick={() => setConfirmCancel(false)}
                >
                  返回
                </Button>
              </div>
            ) : (
              <Button variant="ghost" size="sm" onClick={() => setConfirmCancel(true)}>
                取消任务
              </Button>
            )
          ) : null}
        </header>
      ) : null}
      {controlError === undefined ? null : (
        <DetailReadAlert
          message={controlError}
          onRetry={() => void controlSubagent(task?.state === "suspended")}
        />
      )}
      {controller.detailError === undefined ? null : (
        <DetailReadAlert
          message={controller.detailError}
          onRetry={() => void controller.refreshDetail()}
        />
      )}
      {controller.transcriptError === undefined ? null : (
        <DetailReadAlert
          message={controller.transcriptError}
          onRetry={() => void controller.refreshDetail()}
        />
      )}
      {tab.taskKind === "subagent" && context !== undefined ? (
        <details className="ja-task-context">
          <summary>
            {context.inheritanceMode === "effective_context" ? "继承自主任务" : "仅使用任务简报"}{" "}
            revision {context.parentRevision}
            <ChevronDown aria-hidden="true" />
          </summary>
          <p>{context.inheritedContextSummary ?? "没有额外的继承摘要。"}</p>
          {context.inheritedContextPreview.length === 0 ? null : (
            <ol aria-label="创建时继承的上下文">
              {context.inheritedContextPreview.map((item, index) => (
                <li key={`${item.role}-${index}`}>
                  {item.text === null ? null : <span>{item.text}</span>}
                  {item.attachmentIds.length === 0 ? null : (
                    <small>{item.attachmentIds.length} 个附件</small>
                  )}
                </li>
              ))}
            </ol>
          )}
        </details>
      ) : null}
      {planDetailsOpen ? null : controller.detailLoading && controller.transcript === undefined ? (
        <div className="ja-task-loading" role="status">
          <LoaderCircle className="ja-task-spin" aria-hidden="true" />
          正在读取任务…
        </div>
      ) : (
        <ChatTimeline
          className="ja-task-chat-timeline"
          items={items}
          turns={turns}
          approvals={approvals}
          skills={environment?.skills}
          approvalDecisions={approvalDecisions}
          approvalClosedAt={approvalClosedAt}
          localSubmissions={conversation?.localSubmissions}
          externalRows={externalRows}
          onApprovalDecision={(approval, decision) =>
            conversation !== undefined
              ? conversation.approve(approval, decision)
              : controller.approvalRespond(
                  approval.approvalId,
                  approval.turnId,
                  approval.threadRevision,
                  decision,
                )
          }
          onPrepareRetry={
            conversation === undefined
              ? undefined
              : (_turnId, text) => conversation.updateDraft(text)
          }
          onOpenLink={transcriptActions?.onOpenLink}
          onCopyText={transcriptActions?.onCopyText}
          onReadToolArtifact={transcriptActions?.onReadToolArtifact}
          onOpenAttachmentPreview={transcriptActions?.onOpenAttachmentPreview}
          emptyText="随时开始新的任务"
        />
      )}
      {planDetailsOpen && goal !== undefined ? (
        <div className="ja-task-plan-details">
          <Button variant="ghost" size="sm" onClick={() => onPlanDetailsChange?.(false)}>
            返回对话
          </Button>
          <PlanWorkbench
            model={goal.model}
            planModel={goal.planModel}
            revisions={goal.revisions}
            evidence={goal.evidence}
            loading={goal.loading}
            error={goal.error}
            busyAction={goal.busyAction}
            onRetry={goal.refresh}
            onSaveDraft={goal.saveDraft}
            onDiscardDraft={goal.discardDraft}
            onPropose={goal.propose}
            onFinalizePlan={goal.finalizePlan}
            onExecute={goal.execute}
            onPausePlan={goal.pausePlan}
            onResumePlan={goal.resumePlan}
            onStopPlan={goal.stopPlan}
            onAttachPlan={
              goal.model === undefined ||
              goal.planModel === undefined ||
              (goal.model.goal.status !== "active" && goal.model.goal.status !== "paused") ||
              goal.model.goal.activePlanId === goal.planModel.plan.planId
                ? undefined
                : goal.attachPlan
            }
            onDetachPlan={goal.model?.goal.activePlanId == null ? undefined : goal.detachPlan}
            onReject={goal.reject}
            onPause={goal.pause}
            onResume={goal.resume}
            onContinue={goal.resume}
          />
        </div>
      ) : null}
      {tab.taskKind === "side_task" && conversation !== undefined ? (
        <div
          className="ja-task-composer ja-conversation-content-rail"
          data-task-state={task?.state}
        >
          <Composer
            focusRequest={focusRequest}
            interactionSlot={
              clarification === undefined ? undefined : (
                <InteractionCard controller={clarification} />
              )
            }
            className="ja-task-structured-composer"
            text={conversation.draft}
            onTextChange={conversation.updateDraft}
            threadId={threadId}
            workspaceId={environment?.workspaceId}
            runtimeGeneration={environment?.runtimeGeneration}
            nativeDropEvent={environment?.nativeDropEvent}
            dropZoneRef={environment?.dropZoneRef}
            contextReferences={conversation.contextReferences}
            onContextReferencesChange={conversation.updateContextReferences}
            preferences={conversation.preferences}
            models={conversation.models}
            skills={environment?.skills}
            slashCommands={environment?.slashCommands}
            onSearchWorkspacePaths={environment?.onSearchWorkspacePaths}
            attachments={conversation.attachments}
            attachmentDraftItems={conversation.attachmentDraftItems}
            activeTurn={conversation.activeTurn}
            suspendedTurn={conversation.suspendedTurn}
            awaitingUserInput={clarification?.request?.status === "pending"}
            disabled={conversation.disabled || closing}
            sending={conversation.sending}
            preferenceBusy={conversation.preferenceBusy}
            draftRecoveryRevision={conversation.draftRecoveryRevision}
            importingAttachments={conversation.importingAttachments}
            cancelling={conversation.cancelling}
            resuming={conversation.resuming}
            error={conversation.error ?? goal?.error}
            queuedInputs={conversation.queuedInputs}
            queueAccepting={conversation.queueAccepting}
            contextUsage={contextUsage}
            placeholder={
              conversation.preferences?.collaborationMode === "plan"
                ? "描述需要制定计划的任务…"
                : "随心输入"
            }
            modeStatus={
              conversation.preferences === undefined ? undefined : (
                <ComposerGoalStatus
                  mode={conversation.preferences.collaborationMode}
                  goal={goal?.model?.goal}
                  currentStepTitle={currentStepTitle}
                  busy={conversation.preferenceBusy}
                  onOpenGoal={() => onPlanDetailsChange?.(true)}
                  onOpenPlan={
                    goal?.planModel === undefined ? undefined : () => onPlanDetailsChange?.(true)
                  }
                  onDisablePlan={() => conversation.changeCollaborationMode("default")}
                />
              )
            }
            goalStatus={
              goal?.model === undefined ? undefined : (
                <GoalStatusBar
                  goal={goal.model.goal}
                  evaluation={goal.model.evaluation}
                  currentStepTitle={currentStepTitle}
                  busy={goal.busyAction !== undefined}
                  onOpen={() => onPlanDetailsChange?.(true)}
                  onPause={() => void goal.pause()}
                  onResume={() => void goal.resume()}
                  onResolve={() => onPlanDetailsChange?.(true)}
                  onContinue={() => void goal.resume()}
                  hasPendingQuestion={clarification?.request?.status === "pending"}
                  onAnswerQuestion={() => clarification?.setCollapsed(false)}
                />
              )
            }
            onModelChange={(value) => void conversation.changeModel(value)}
            onReasoningChange={(value) => void conversation.changeReasoning(value)}
            onAccessModeChange={(value) => void conversation.changeAccessMode(value)}
            onRestoreDefaults={environment?.onRestoreDefaults}
            onAddAttachments={
              environment?.attachmentPort === undefined ? undefined : conversation.importAttachments
            }
            onRetryAttachment={
              environment?.attachmentPort === undefined ? undefined : conversation.retryAttachment
            }
            onRemoveAttachment={
              environment?.attachmentPort === undefined ? undefined : conversation.removeAttachment
            }
            onPasteAttachments={
              environment?.attachmentPort === undefined ? undefined : conversation.importClipboard
            }
            onDropAttachments={
              environment?.attachmentPort === undefined
                ? undefined
                : conversation.importDroppedAttachments
            }
            onOpenAttachmentPreview={environment?.onOpenAttachmentPreview}
            onOpenQueuedAttachmentPreview={environment?.onOpenQueuedAttachmentPreview}
            onOpenWorkspaceReference={environment?.onOpenWorkspaceReference}
            onSend={conversation.send}
            onEnqueue={conversation.enqueue}
            onPrioritizeQueuedInput={conversation.prioritizeQueuedInput}
            onUpdateQueuedInput={conversation.updateQueuedInput}
            onDeleteQueuedInput={conversation.deleteQueuedInput}
            onResume={conversation.resume}
            onCancel={conversation.cancel}
          />
        </div>
      ) : null}
    </section>
  );
}
