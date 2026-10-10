// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Bot, ChevronDown, CircleAlert, LoaderCircle } from "lucide-react";
import { useEffect, useMemo, useState, type ComponentProps, type ReactElement } from "react";
import { useShallow } from "zustand/react/shallow";
import {
  Composer,
  ConversationTimelineSurface,
  InteractionCard,
  resolveContextUsage,
  selectApprovalClosedAtForThread,
  selectApprovalDecisionsForThread,
  selectApprovalsForThread,
  selectTurnsForThread,
  useTimelineStore,
  type ComposerSlashCommand,
  type ComposerNativeDropEvent,
  type ComposerWorkspaceSearchResult,
  type ConversationAttachment,
  type ConversationAttachmentPort,
  type ConversationContextReference,
  type ConversationInteractionController,
  type ConversationUsageReader,
  type HistoryAttachmentThumbnailPort,
  type InteractionController,
} from "@/features/conversation";
import {
  ComposerPlanStatus,
  PlanTimelineBlock,
  PlanWorkbench,
  type PlanController,
} from "@/features/plans";
import { Button, ErrorState } from "@/shared/ui/primitives";
import type { WorkbenchTaskTab } from "@/features/workbench";
import { isTaskRevisionConflict, type TaskController } from "../application/useTaskController";
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
  /** 侧聊必须用自身 Thread 读取同一份服务端账本，不能借用主会话快照。 */
  readonly usageReader?: ConversationUsageReader;
  readonly nativeDropEvent?: ComposerNativeDropEvent;
  readonly dropZoneRef?: (element: HTMLFormElement | null) => void;
  readonly skills?: readonly TaskComposerSkillSuggestion[];
  readonly attachmentPort?: ConversationAttachmentPort;
  readonly attachmentThumbnailPort?: HistoryAttachmentThumbnailPort;
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
  readonly onReadMessageContent?: (threadId: string, itemId: string) => Promise<string>;
  readonly onReadAnswerContent?: (
    threadId: string,
    finalMessageId: string,
    minimumRevision?: number,
    turnId?: string,
  ) => Promise<string>;
  readonly onReadToolArtifact?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    artifactId: string;
  }) => Promise<string>;
  readonly onResolveToolRecovery?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    expectedThreadRevision: number;
    expectedRecoveryRevision: number;
    decision: "retry" | "skip";
    operationKey: string;
  }) => Promise<unknown>;
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

/** 读失败保留原会话和草稿；无重试入口的提示可与原确认控件共存，避免重复副作用按钮。 */
function DetailReadAlert({
  message,
  onRetry,
}: {
  message: string;
  onRetry?: () => void;
}): ReactElement {
  return (
    <div className="ja-task-read-alert" role="alert">
      <CircleAlert aria-hidden="true" />
      <span>{message}</span>
      {onRetry === undefined ? null : (
        <Button variant="ghost" size="sm" onClick={onRetry}>
          重试
        </Button>
      )}
    </div>
  );
}

/** Timeline subscription and terminal-Plan reconciliation exist only while this Task Thread is visible. */
function TaskConversationTimeline({
  threadId,
  controller,
  conversation,
  clarification,
  plan,
  environment,
  transcriptActions,
  externalRows,
}: {
  threadId: string;
  controller: TaskController;
  conversation?: ConversationInteractionController;
  clarification?: InteractionController;
  plan?: PlanController;
  environment?: TaskComposerEnvironment;
  transcriptActions?: TaskTranscriptActions;
  externalRows?: ComponentProps<typeof ConversationTimelineSurface>["externalRows"];
}): ReactElement {
  const turns = useTimelineStore(useShallow(selectTurnsForThread(threadId)));
  const approvals = useTimelineStore(useShallow(selectApprovalsForThread(threadId)));
  const approvalDecisions = useTimelineStore(
    useShallow(selectApprovalDecisionsForThread(threadId)),
  );
  const approvalClosedAt = useTimelineStore(useShallow(selectApprovalClosedAtForThread(threadId)));
  const latestTerminalTurnId = useMemo(
    () =>
      turns
        .filter((turn) => ["completed", "failed", "cancelled"].includes(turn.status))
        .sort((left, right) => (left.completedAt ?? "").localeCompare(right.completedAt ?? ""))
        .at(-1)?.turnId,
    [turns],
  );
  const currentPlanId = plan?.planModel?.plan.planId;
  const refreshPlan = plan?.refresh;
  /** A visible terminal turn may have committed Plan facts not present in its earlier Thread snapshot. */
  useEffect(() => {
    if (currentPlanId !== undefined && latestTerminalTurnId !== undefined) void refreshPlan?.();
  }, [currentPlanId, latestTerminalTurnId, refreshPlan]);

  return (
    <ConversationTimelineSurface
      threadId={threadId === "" ? undefined : threadId}
      answeredRequest={clarification?.answeredRequest ?? null}
      answeredAnswers={clarification?.answeredAnswers ?? {}}
      turns={turns}
      approvals={approvals}
      approvalDecisions={approvalDecisions}
      approvalClosedAt={approvalClosedAt}
      skills={environment?.skills}
      localSubmissions={conversation?.localSubmissions}
      editableSourceMessageId={conversation?.editableSourceMessageId}
      onEditQuestion={conversation?.editQuestion}
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
      onOpenLink={transcriptActions?.onOpenLink}
      onCopyText={transcriptActions?.onCopyText}
      onReadMessageContent={
        transcriptActions?.onReadMessageContent === undefined
          ? undefined
          : (_threadId, itemId) => transcriptActions.onReadMessageContent!(threadId, itemId)
      }
      onReadAnswerContent={
        transcriptActions?.onReadAnswerContent === undefined
          ? undefined
          : (finalMessageId, minimumRevision, turnId) =>
              transcriptActions.onReadAnswerContent!(
                threadId,
                finalMessageId,
                minimumRevision,
                turnId,
              )
      }
      onReadToolArtifact={transcriptActions?.onReadToolArtifact}
      onResolveToolRecovery={transcriptActions?.onResolveToolRecovery}
      onOpenAttachmentPreview={transcriptActions?.onOpenAttachmentPreview}
      emptyText="随时开始新的任务"
      className="ja-task-chat-timeline"
    />
  );
}

/**
 * 侧聊拥有真实 Thread，和主任务使用相同的交互 controller、Timeline、Composer 与 Plan
 * 组件。视图不再创建精简版草稿或解析历史格式；Subagent 保持只读正文和显式控制入口。
 */
export function TaskDetailPanel({
  tab,
  controller,
  composerEnvironment: environment,
  transcriptActions,
  conversation,
  plan,
  clarification,
  active = true,
  planDetailsOpen = false,
  onPlanDetailsChange,
  focusRequest,
}: {
  tab: WorkbenchTaskTab;
  controller: TaskController;
  composerEnvironment?: TaskComposerEnvironment;
  transcriptActions?: TaskTranscriptActions;
  conversation?: ConversationInteractionController;
  plan?: PlanController;
  clarification?: InteractionController;
  /** Hidden retained Workbench hosts must not observe stream-heavy Thread state or start Workspace IO. */
  active?: boolean;
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
  /** Subagent 的递归取消保持显式确认；冲突退回入口，普通失败保留原确认以供用户显式重试。 */
  const controlSubagent = async (resume: boolean): Promise<void> => {
    if (task === undefined || controlBusy) return;
    setControlBusy(true);
    setControlError(undefined);
    try {
      if (resume) await controller.resume(task);
      else await controller.cancel(task);
      setConfirmCancel(false);
    } catch (error) {
      const revisionConflict = !resume && isTaskRevisionConflict(error);
      if (revisionConflict) setConfirmCancel(false);
      setControlError(
        revisionConflict
          ? error instanceof Error
            ? error.message
            : "任务状态已更新，请查看最新任务及取消范围后重新确认。"
          : resume
            ? "任务恢复失败，请重试。"
            : "取消请求未完成，请重试。",
      );
    } finally {
      setControlBusy(false);
    }
  };
  const hasTimelineSnapshot = useTimelineStore(
    (state) => state.snapshotRevisionByThread[threadId] !== undefined,
  );
  const usage = useTimelineStore((state) => state.contextUsageByThread[threadId]);
  const invalidatedAt = useTimelineStore(
    (state) => state.contextUsageInvalidatedAtByThread[threadId],
  );
  const compaction = useTimelineStore((state) => state.contextCompactionByThread[threadId]);
  const contextUsage = useMemo(
    () => resolveContextUsage({ usage, invalidatedAt, compaction }),
    [usage, invalidatedAt, compaction],
  );
  /** 与主 Composer 保持同一刷新语义：仅新的请求身份或恢复事实才能刷新已打开浮层。 */
  const usageRefreshRevision = [
    usage?.modelRequestId,
    usage?.modelRequestOrdinal,
    usage?.modelRound,
    usage?.measuredAt,
    invalidatedAt,
    compaction?.phase,
    compaction?.occurredAt,
  ].join("\u0000");
  const context =
    controller.detail?.task.taskThreadId === threadId ? controller.detail.contextSeed : undefined;
  const closing = controller.closingTaskThreadId === threadId;
  const externalRows = [] as {
    rowId: string;
    occurredAt: string;
    revision: string;
    content: ReactElement;
  }[];
  if (plan?.planModel !== undefined)
    externalRows.push({
      rowId: `plan:${plan.planModel.plan.planId}`,
      occurredAt: plan.planModel.plan.updatedAt,
      revision: String(plan.planModel.plan.revision),
      content: (
        <PlanTimelineBlock
          planModel={plan.planModel}
          busy={plan.busyAction !== undefined}
          onOpenDetails={() => onPlanDetailsChange?.(true)}
          onExecute={plan.execute}
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
            controller.error === undefined &&
            controller.detailError === undefined &&
            ["queued", "running", "waiting_approval", "waiting_input"].includes(task.state) ? (
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
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setControlError(undefined);
                  setConfirmCancel(true);
                }}
              >
                取消任务
              </Button>
            )
          ) : null}
        </header>
      ) : null}
      {controlError === undefined ? null : <DetailReadAlert message={controlError} />}
      {controller.error === undefined ? null : (
        <DetailReadAlert message={controller.error} onRetry={() => void controller.refresh()} />
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
      {planDetailsOpen ? null : controller.detailLoading && !hasTimelineSnapshot ? (
        <div className="ja-task-loading" role="status">
          <LoaderCircle className="ja-task-spin" aria-hidden="true" />
          正在读取任务…
        </div>
      ) : active ? (
        <TaskConversationTimeline
          threadId={threadId}
          controller={controller}
          conversation={conversation}
          clarification={clarification}
          plan={plan}
          environment={environment}
          transcriptActions={transcriptActions}
          externalRows={externalRows}
        />
      ) : null}
      {planDetailsOpen && plan !== undefined ? (
        <div className="ja-task-plan-details">
          <Button variant="ghost" size="sm" onClick={() => onPlanDetailsChange?.(false)}>
            返回对话
          </Button>
          <PlanWorkbench
            planModel={plan.planModel}
            revisions={plan.revisions}
            evidence={plan.evidence}
            loading={plan.loading}
            error={plan.error}
            busyAction={plan.busyAction}
            onRetry={plan.refresh}
            onSaveDraft={plan.saveDraft}
            onDiscardDraft={plan.discardDraft}
            onPropose={plan.propose}
            onFinalizePlan={plan.finalizePlan}
            onExecute={plan.execute}
            onPausePlan={plan.pausePlan}
            onResumePlan={plan.resumePlan}
            onStopPlan={plan.stopPlan}
            onReject={plan.reject}
          />
        </div>
      ) : null}
      {tab.taskKind === "side_task" && conversation !== undefined ? (
        <div
          className="ja-task-composer ja-conversation-content-rail"
          data-task-state={task?.state}
        >
          <Composer
            active={active}
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
            nativeDropEvent={active ? environment?.nativeDropEvent : undefined}
            dropZoneRef={active ? environment?.dropZoneRef : undefined}
            contextReferences={conversation.contextReferences}
            onContextReferencesChange={conversation.updateContextReferences}
            preferences={conversation.preferences}
            models={conversation.models}
            skills={environment?.skills}
            slashCommands={environment?.slashCommands}
            onSearchWorkspacePaths={environment?.onSearchWorkspacePaths}
            attachments={conversation.attachments}
            attachmentDraftItems={conversation.attachmentDraftItems}
            attachmentThumbnailPort={environment?.attachmentThumbnailPort}
            activeTurn={conversation.activeTurn}
            suspendedTurn={conversation.suspendedTurn}
            continuationAvailable={conversation.continuationAvailable}
            editingQuestion={conversation.editingQuestion}
            onCancelEdit={conversation.cancelEditQuestion}
            interactionPresentation={
              clarification?.request?.status !== "pending"
                ? "none"
                : clarification.collapsed
                  ? "collapsed"
                  : "expanded"
            }
            disabled={conversation.disabled || closing}
            sending={conversation.sending}
            preferenceBusy={conversation.preferenceBusy}
            draftRecoveryRevision={conversation.draftRecoveryRevision}
            importingAttachments={conversation.importingAttachments}
            cancelling={conversation.cancelling}
            resuming={conversation.resuming}
            error={conversation.error ?? plan?.error}
            queuedInputs={conversation.queuedInputs}
            queueAccepting={conversation.queueAccepting}
            contextUsage={contextUsage}
            usageReader={environment?.usageReader}
            usageRefreshRevision={usageRefreshRevision}
            placeholder={
              conversation.preferences?.collaborationMode === "plan"
                ? "描述需要制定计划的任务…"
                : "随心输入"
            }
            modeStatus={
              conversation.preferences === undefined ? undefined : (
                <ComposerPlanStatus
                  mode={conversation.preferences.collaborationMode}
                  busy={conversation.preferenceBusy}
                  onOpenPlan={
                    plan?.planModel === undefined ? undefined : () => onPlanDetailsChange?.(true)
                  }
                  onDisablePlan={() => conversation.changeCollaborationMode("default")}
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
            onContinue={conversation.continueReply}
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
