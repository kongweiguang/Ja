// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { FolderOpen } from "lucide-react";
import { useCallback, useEffect, useMemo, type ReactElement } from "react";
import { useShallow } from "zustand/react/shallow";
import {
  ChatTimeline,
  Composer,
  ConversationSummaryPopover,
  isWorkItem,
  itemChangedFiles,
  itemDiffStat,
  ReplyFileOpenMenu,
  ThreadOperationsMenu,
  selectApprovalClosedAt,
  selectApprovalDecisions,
  selectApprovals,
  selectItemsForThread,
  selectGoalActivitiesForOwner,
  turnDurationMs,
  turnStatusLabel,
  resolveContextUsage,
  useConversationInteractionController,
  useReplyFileOpen,
  useTimelineStore,
  type ConversationController,
  type ConversationAttachmentPort,
  type ConversationArtifactPort,
  type ConversationContextReference,
  type ConversationSummary,
  type ComposerSlashCommand,
  type TimelineTurn as Turn,
  type TimelineGoalActivity,
} from "@/features/conversation";
import type { SettingsController } from "@/features/settings";
import type { AttachmentPreviewPort, AttachmentPreviewTarget } from "@/features/workbench/preview";
import type { TurnReviewTarget } from "@/features/workbench/review";
import type { WorkspaceController } from "@/features/workspace";
import type { NativeDropEvent } from "@/api/tauri/nativeDrop";
import { TaskActivityCard, useTaskActivityTimeline, type TaskSummary } from "@/features/tasks";
import {
  ComposerGoalStatus,
  GoalActivityCard,
  GoalStatusBar,
  PlanTimelineBlock,
  type CollaborationMode,
  type GoalController,
} from "@/features/goals";
import { RightPanelIcon } from "@/shared/ui/RightPanelIcon";
import type { JaWorkbenchAdapters } from "../useJaWorkbench";
import { useRuntimeLifecycle, useRuntimeState, useRuntimeTurns } from "../RuntimeProvider";
import { IconButton } from "@/shared/ui/primitives";
import { modelSelectionId } from "@/shared/settings/types";
import { useLatestTurnReviewPublisher } from "../application/useLatestTurnReviewPublisher";

export interface ConversationWorkspaceProps {
  readonly workspace: WorkspaceController;
  readonly conversation: ConversationController;
  readonly settings: SettingsController;
  readonly inspectorOpen: boolean;
  readonly onToggleInspector: () => void;
  readonly summaryContext: Pick<ConversationSummary, "scope" | "gitBranch" | "model" | "runtime">;
  readonly workspaceAdapter: JaWorkbenchAdapters["workspace"];
  readonly onOpenLink: (url: string) => Promise<void>;
  readonly onCopyText: (text: string) => Promise<void>;
  readonly onConversationFocusAvailabilityChange: (available: boolean) => void;
  readonly onRegisterWorkspaceReferenceTarget: (
    target: ((reference: ComposerWorkspaceReferenceTarget) => void) | undefined,
  ) => void;
  readonly onOpenWorkspaceReference: (
    reference: ComposerWorkspaceReferenceTarget,
    source: HTMLButtonElement,
  ) => void;
  readonly attachmentPort?: ConversationAttachmentPort;
  readonly attachmentPreviewPort?: AttachmentPreviewPort;
  readonly artifactPort?: ConversationArtifactPort;
  readonly nativeDropEvent?: NativeDropEvent;
  readonly onRegisterComposerDropZone?: (element: HTMLFormElement | null) => void;
  readonly onOpenAttachmentPreview?: (
    target: AttachmentPreviewTarget,
    source: HTMLButtonElement,
  ) => void;
  readonly onLatestTurnReviewChange: (target: TurnReviewTarget | undefined) => void;
  readonly onOpenTurnReview: (
    target: TurnReviewTarget,
    scopeLabel?: string,
    requestedPath?: string,
  ) => boolean;
  readonly onAttachmentRemoved?: (attachmentId: string) => void;
  readonly onAttachmentsBound?: (threadId: string, attachmentIds: readonly string[]) => void;
  readonly slashCommands?: readonly ComposerSlashCommand[];
  readonly onOpenTask: (task: TaskSummary) => void;
  readonly planGoalAvailable: boolean;
  readonly goal: GoalController;
  readonly onOpenGoal: () => void;
}

export type ComposerWorkspaceReferenceTarget = Extract<
  ConversationContextReference,
  { type: "workspace_reference" }
>;

/** 一次性打开请求同时携带作用域与序号，Workbench 不得消费上一 Workspace 的迟到意图。 */
export interface WorkspaceReferencePreviewRequest {
  readonly requestId: number;
  readonly reference: ComposerWorkspaceReferenceTarget;
}

/** Workbench 只回传稳定结果，原生读取错误细节继续由 Files 的脱敏通知边界拥有。 */
export type WorkspaceReferencePreviewOutcome = "opened" | "failed" | "closed";

/** `/plan` 无参数时切换，on/off 参数提供可脚本化的确定结果；其它参数失败关闭。 */
function planModeFromArgument(current: CollaborationMode, argument: string): CollaborationMode {
  const normalized = argument.normalize("NFKC").trim().toLocaleLowerCase();
  if (normalized === "" || normalized === "toggle") return current === "plan" ? "default" : "plan";
  if (["on", "plan", "开启", "打开", "计划"].includes(normalized)) return "plan";
  if (["off", "default", "关闭", "执行"].includes(normalized)) return "default";
  throw new Error("unsupported plan mode argument");
}

/**
 * 对话工作区只负责组合 Conversation、Workspace、Settings 与 Runtime 窄端口，并把 application
 * view model 绑定到 UI；Timeline 摘要在已订阅该状态的边界内派生，避免把高频 delta 提升到应用壳。
 * 启动期间保留对话外壳但遵守发送准入；生命周期提示由侧栏统一承载。
 */
export function ConversationWorkspace({
  workspace,
  conversation,
  settings,
  inspectorOpen,
  onToggleInspector,
  summaryContext,
  workspaceAdapter,
  onOpenLink,
  onCopyText,
  onConversationFocusAvailabilityChange,
  onRegisterWorkspaceReferenceTarget,
  onOpenWorkspaceReference,
  attachmentPort,
  attachmentPreviewPort,
  artifactPort,
  nativeDropEvent,
  onRegisterComposerDropZone,
  onOpenAttachmentPreview,
  onLatestTurnReviewChange,
  onOpenTurnReview,
  onAttachmentRemoved,
  onAttachmentsBound,
  slashCommands,
  onOpenTask,
  planGoalAvailable,
  goal,
  onOpenGoal,
}: ConversationWorkspaceProps): ReactElement {
  const { boot, turnAdmissionReady, runtimeState } = useRuntimeState();
  const { queryRuntime } = useRuntimeLifecycle();
  const turnPort = useRuntimeTurns();
  const threadId = conversation.currentThreadId ?? "";
  const taskActivities = useTaskActivityTimeline(
    threadId === "" ? undefined : threadId,
    runtimeState?.generation,
  );
  /**
   * Task Activity 只映射持久摘要与稳定 identity；Child Transcript 不进入父 Conversation reducer，
   * 统一 Timeline 的排序与虚拟化由 ChatTimeline 在展示边界完成。
   */
  const taskTimelineRows = useMemo(
    () =>
      taskActivities.map(({ activity, task }) => ({
        rowId: activity.activityId,
        occurredAt: activity.createdAt,
        revision: `${activity.activitySequence}:${task.revision}`,
        content: <TaskActivityCard activity={activity} task={task} onOpen={onOpenTask} />,
      })),
    [onOpenTask, taskActivities],
  );
  const persistedGoalActivities = useTimelineStore(selectGoalActivitiesForOwner(threadId));
  /**
   * mutation ACK 可先于下一次 thread/read 到达；把同一服务端 Goal 投影临时并入持久基线，
   * 让常驻状态行原位释放时终态卡片立即出现，后续快照按 goalId 去重接管。
   */
  const goalActivities = useMemo<readonly TimelineGoalActivity[]>(() => {
    const current = goal.model?.goal;
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
        eventSequence: goal.model?.eventSequence ?? current.revision,
        occurredAt: current.updatedAt,
      },
    ].sort((left, right) => left.eventSequence - right.eventSequence);
  }, [goal.model, persistedGoalActivities, threadId]);
  const goalTimelineRows = useMemo(
    () =>
      goalActivities.map((activity) => ({
        rowId: `goal-terminal:${activity.goalId}`,
        occurredAt: activity.occurredAt,
        revision: `${activity.goalRevision}:${activity.eventSequence}`,
        content: <GoalActivityCard activity={activity} />,
      })),
    [goalActivities],
  );
  /**
   * 当前冻结 Plan 作为 Timeline 的结构化事实展示；详情入口显式打开 capability，组件本身不让
   * PlanWorkbench 常驻，也不从 agent Markdown 推导步骤或验收状态。
   */
  const planTimelineRows = useMemo(() => {
    const planModel = goal.planModel;
    const model = goal.model;
    if (planModel === undefined || planModel.plan.ownerThreadId !== threadId) return [];
    return [
      {
        rowId: `plan:${planModel.plan.planId}:${planModel.revision?.planRevisionId ?? "draft"}`,
        occurredAt: planModel.revision?.createdAt ?? planModel.plan.createdAt,
        revision: `${planModel.plan.revision}:${planModel.eventSequence}`,
        content: (
          <PlanTimelineBlock model={model} planModel={planModel} onOpenDetails={onOpenGoal} />
        ),
      },
    ];
  }, [goal.model, goal.planModel, onOpenGoal, threadId]);
  const items = useTimelineStore(
    useShallow((state) => (threadId === "" ? [] : selectItemsForThread(threadId)(state))),
  );
  const turns = useTimelineStore(
    useShallow((state) =>
      threadId === ""
        ? []
        : Object.values(state.turns).filter((turn) => turn.threadId === threadId),
    ),
  );
  const latestTerminalPlanTurn = useMemo(
    () =>
      turns
        .filter((turn) => ["completed", "failed", "cancelled"].includes(turn.status))
        .sort(
          (left, right) =>
            (left.completedAt ?? "").localeCompare(right.completedAt ?? "") ||
            (left.threadRevision ?? 0) - (right.threadRevision ?? 0),
        )
        .at(-1),
    [turns],
  );
  const currentPlanId = goal.planModel?.plan.planId;
  const latestTerminalPlanTurnId = latestTerminalPlanTurn?.turnId;
  const refreshGoal = goal.refresh;
  /**
   * standalone Plan 没有 Goal event 可借用；Plan mode Turn 到达终态后显式回读 Plan 聚合，
   * 让 agent 提出的 revision/approval 状态进入 UI，同时不在流式阶段轮询或扫描 Workspace。
   */
  useEffect(() => {
    if (currentPlanId === undefined || latestTerminalPlanTurnId === undefined) return;
    void refreshGoal();
  }, [currentPlanId, latestTerminalPlanTurnId, refreshGoal]);
  const latestTurnReview = useMemo<TurnReviewTarget | undefined>(() => {
    const workspaceId = workspace.workspace?.workspaceId;
    if (workspaceId === undefined) return undefined;
    // Object insertion order follows the committed Timeline sequence；只取最后一个终态 Turn，
    // 该轮无可靠非零修改时必须返回空，不能回退展示更早的旧修改。
    const latestTerminal = [...turns]
      .reverse()
      .find((turn) => ["completed", "failed", "cancelled"].includes(turn.status));
    const changeSet = latestTerminal?.changeSet;
    if (
      latestTerminal === undefined ||
      changeSet == null ||
      changeSet.stats.files === 0 ||
      changeSet.artifactId === undefined ||
      latestTerminal.threadRevision === undefined
    )
      return undefined;
    return {
      workspaceId,
      threadId: latestTerminal.threadId,
      turnId: latestTerminal.turnId,
      kind: "frozen_turn",
      threadRevision: latestTerminal.threadRevision,
      turnNumber: turns.findIndex((turn) => turn.turnId === latestTerminal.turnId) + 1,
      completedAt: latestTerminal.completedAt,
      state: changeSet.state,
      incompleteReasons: changeSet.incompleteReasons,
      files: changeSet.files,
      stats: changeSet.stats,
      artifactId: changeSet.artifactId,
    };
  }, [turns, workspace.workspace?.workspaceId]);

  /**
   * 终态卡按自身 Turn 构造 frozen target；不能复用 latest target，否则后续 Turn 会让旧卡打开
   * 错误 artifact。缺失 revision 的历史记录只展示冻结事实，不伪造可审核入口。
   */
  const openFrozenTurnReview = useCallback(
    (turn: Turn, changeSet: NonNullable<Turn["changeSet"]>, requestedPath?: string): void => {
      const workspaceId = workspace.workspace?.workspaceId;
      if (
        workspaceId === undefined ||
        turn.threadRevision === undefined ||
        changeSet.artifactId === undefined ||
        changeSet.stats.files === 0
      )
        return;
      const turnNumber = turns.findIndex((candidate) => candidate.turnId === turn.turnId) + 1;
      onOpenTurnReview(
        {
          kind: "frozen_turn",
          workspaceId,
          threadId: turn.threadId,
          turnId: turn.turnId,
          threadRevision: turn.threadRevision,
          turnNumber,
          completedAt: turn.completedAt,
          state: changeSet.state,
          incompleteReasons: changeSet.incompleteReasons,
          files: changeSet.files,
          stats: changeSet.stats,
          artifactId: changeSet.artifactId,
        },
        turnNumber > 0 ? `第 ${turnNumber} 轮修改` : "所选轮次修改",
        requestedPath,
      );
    },
    [onOpenTurnReview, turns, workspace.workspace?.workspaceId],
  );

  useLatestTurnReviewPublisher({
    workspaceId: workspace.workspace?.workspaceId,
    threadId,
    target: latestTurnReview,
    publish: onLatestTurnReviewChange,
  });
  /**
   * 在 Conversation 自身的 Timeline 订阅边界内归约摘要；缺失指标继续缺失，不能用零伪造证据，
   * 也不能为了头部 Popover 让每个流式片段重新渲染 Navigation 与整个应用壳。
   */
  const summary = useMemo<ConversationSummary>(() => {
    const changedFileMetrics = items
      .map(itemChangedFiles)
      .filter((value): value is number => value !== undefined);
    const diffMetrics = items
      .map(itemDiffStat)
      .filter(
        (value): value is NonNullable<ReturnType<typeof itemDiffStat>> => value !== undefined,
      );
    const durations = turns
      .map(turnDurationMs)
      .filter((value): value is number => value !== undefined);
    const activeStatus = turns.find(
      (turn) => !["completed", "failed", "cancelled"].includes(turn.status),
    )?.status;
    return {
      ...summaryContext,
      status: activeStatus === undefined ? undefined : turnStatusLabel(activeStatus),
      turnCount: turns.length,
      stepCount: items.filter(isWorkItem).length,
      changedFiles:
        changedFileMetrics.length === 0
          ? undefined
          : changedFileMetrics.reduce((total, value) => total + value, 0),
      additions: diffMetrics.some((value) => value.additions !== undefined)
        ? diffMetrics.reduce((total, value) => total + (value.additions ?? 0), 0)
        : undefined,
      deletions: diffMetrics.some((value) => value.deletions !== undefined)
        ? diffMetrics.reduce((total, value) => total + (value.deletions ?? 0), 0)
        : undefined,
      durationMs:
        durations.length === 0 ? undefined : durations.reduce((total, value) => total + value, 0),
    };
  }, [items, summaryContext, turns]);
  const contextFacts = useTimelineStore(
    useShallow((state) => {
      if (threadId === "") return { usage: undefined, compaction: undefined };
      const usage = state.contextUsageByThread[threadId];
      return {
        usage,
        compaction: state.contextCompactionByThread[threadId],
      };
    }),
  );
  const approvals = useTimelineStore(
    useShallow((state) =>
      threadId === ""
        ? []
        : selectApprovals(state).filter((approval) => approval.threadId === threadId),
    ),
  );
  const approvalDecisions = useTimelineStore(useShallow((state) => selectApprovalDecisions(state)));
  const approvalClosedAt = useTimelineStore(useShallow((state) => selectApprovalClosedAt(state)));
  const currentThread = conversation.threads.find(
    (candidate) => candidate.threadId === conversation.currentThreadId,
  );
  const isProjectScope = workspace.workspace?.kind === "project";
  const replyFileOpen = useReplyFileOpen(
    isProjectScope ? workspace.workspace?.workspaceId : undefined,
    items,
    workspaceAdapter,
  );
  /** 模型目录只投影 Thread 偏好需要的稳定身份和能力，不复制 Provider 连接配置。 */
  const models = useMemo(
    () =>
      settings.snapshot.providers.flatMap((provider) =>
        provider.models.map((model) => ({
          value: modelSelectionId(provider.providerId, model.modelId),
          providerId: provider.providerId,
          providerLabel: provider.name,
          modelId: model.modelId,
          modelIdentifier: model.model,
          modelLabel: model.name,
          contextWindowTokens: model.capabilities.contextWindowTokens,
          reasoningLevelMap: model.reasoningLevelMap,
          defaultReasoningLevel: model.defaultReasoningLevel,
        })),
      ),
    [settings.snapshot.providers],
  );
  const contextUsage = resolveContextUsage({
    usage: contextFacts.usage,
    compaction: contextFacts.compaction,
  });
  // Workspace commit 与配置 Query key 切换可能跨一个 React effect；只有目标 scope 已取得
  // 权威快照才开放 Composer，防止旧项目 Provider 配置进入新项目 Turn。
  const settingsScopeReady =
    settings.scopeReady &&
    settings.scopeWorkspaceId ===
      (workspace.workspace?.kind === "project" ? workspace.workspace.workspaceId : undefined);
  const ready =
    settingsScopeReady && turnAdmissionReady && (boot.status === "ready" || boot.status === "busy");
  const interaction = useConversationInteractionController({
    threadId: conversation.currentThreadId,
    workspaceId: workspace.workspace?.workspaceId,
    preferences: currentThread?.preferences ?? undefined,
    models,
    ready,
    blocked: workspace.busy || conversation.busy,
    turnPort,
    planCreationPort: planGoalAvailable ? { create: goal.createPlan } : undefined,
    preferencesPort: { updatePreferences: conversation.updatePreferences },
    attachmentPort,
    onAttachmentRemoved,
    onAttachmentsBound,
  });
  const { contextReferences, updateContextReferences } = interaction;
  /** 右侧 Files 只投递相对路径引用；当前 Thread 的草稿 owner 负责去重与后续发送组装。 */
  const appendWorkspaceReference = useCallback(
    (reference: ComposerWorkspaceReferenceTarget): void => {
      if (reference.workspaceId !== workspace.workspace?.workspaceId) return;
      updateContextReferences([...contextReferences, reference]);
    },
    [contextReferences, updateContextReferences, workspace.workspace?.workspaceId],
  );
  /** 注册表随当前 Thread 草稿更新，卸载时立即撤销，避免右栏写入已离开的会话。 */
  useEffect(() => {
    onRegisterWorkspaceReferenceTarget(appendWorkspaceReference);
    return () => onRegisterWorkspaceReferenceTarget(undefined);
  }, [appendWorkspaceReference, onRegisterWorkspaceReferenceTarget]);
  /** @ 只调用 App Server 的有界路径目录；结果携带身份供 Composer 再做迟到栅栏。 */
  const searchWorkspacePaths = useCallback(
    (query: string) => {
      const workspaceId = workspace.workspace?.workspaceId;
      const currentThreadId = conversation.currentThreadId;
      if (workspaceId === undefined || currentThreadId === undefined)
        return Promise.reject(new Error("workspace context unavailable"));
      return queryRuntime("workspace/path/search", {
        threadId: currentThreadId,
        workspaceId,
        query,
        limit: 50,
      });
    },
    [conversation.currentThreadId, queryRuntime, workspace.workspace?.workspaceId],
  );
  /** `$` 只投影当前配置代际中已启用且健康的 Skill 摘要，不在输入时读取 SKILL.md。 */
  const composerSkills = useMemo(
    () =>
      settings.snapshot.skills.flatMap((skill) =>
        skill.enabled && skill.status === "ready"
          ? [
              {
                skillId: skill.id,
                name: skill.name,
                description: skill.description,
                scope: skill.source,
              },
            ]
          : [],
      ),
    [settings.snapshot.skills],
  );
  /**
   * Goal/Plan slash actions 绑定当前 Thread 的真实 controller：Plan 只改协作模板，Goal 只创建
   * 持久聚合；两者都不读写 AccessMode，也不会把命令文本提交给模型。
   */
  const goalPlanSlashCommands: ComposerSlashCommand[] = [
    {
      id: "plan",
      name: "plan",
      aliases: ["计划"],
      group: "添加",
      icon: "plan",
      label: "计划",
      description: "先制定计划再决定是否执行",
      available:
        planGoalAvailable &&
        interaction.preferences !== undefined &&
        !interaction.preferenceBusy &&
        !interaction.activeTurn,
      unavailableReason: interaction.activeTurn ? "当前运行结束后可切换" : "当前会话偏好尚未就绪",
      argument: {
        mode: "optional",
        label: "计划模式",
        placeholder: "输入 on 或 off",
      },
      execute: async ({ argument }) => {
        const preferences = interaction.preferences;
        if (preferences === undefined) throw new Error("thread preferences unavailable");
        await interaction.changeCollaborationMode(
          planModeFromArgument(preferences.collaborationMode, argument),
        );
      },
    },
    {
      id: "goal",
      name: "goal",
      aliases: ["目标"],
      group: "添加",
      icon: "goal",
      label: "目标",
      description: "设置要持续追求的目标",
      available:
        planGoalAvailable &&
        threadId !== "" &&
        goal.model === undefined &&
        !interaction.activeTurn &&
        goal.busyAction === undefined,
      unavailableReason:
        goal.model !== undefined
          ? "当前会话已有活跃目标"
          : interaction.activeTurn
            ? "当前运行结束后可创建目标"
            : "当前会话尚未就绪",
      argument: {
        mode: "required",
        label: "目标",
        placeholder: "描述目标",
      },
      execute: async ({ argument }) => {
        if (threadId === "" || !(await goal.create(threadId, argument)))
          throw new Error("goal creation was not acknowledged");
      },
    },
  ];
  const hasConversationContent =
    items.length > 0 ||
    turns.length > 0 ||
    approvals.length > 0 ||
    interaction.localSubmissions.length > 0 ||
    taskActivities.length > 0 ||
    planTimelineRows.length > 0;
  const scopeName = isProjectScope
    ? (workspace.workspace?.displayName ?? "未命名项目")
    : "无项目对话";
  const threadTitle =
    conversation.threads.find((thread) => thread.threadId === threadId)?.title.trim() || scopeName;
  // 生命周期故障统一在侧栏解释，避免同一原因再次占据对话正文。
  const scopeError =
    boot.status === "ready" || boot.status === "busy" ? workspace.error : undefined;

  /** Composer 可用性只作为壳层快捷键 capability 发布，卸载时立即撤销。 */
  useEffect(() => {
    onConversationFocusAvailabilityChange(!interaction.disabled);
    return () => onConversationFocusAvailabilityChange(false);
  }, [interaction.disabled, onConversationFocusAvailabilityChange]);

  // 空 Thread 与有内容 Thread 共用同一受控 Composer，视觉 dock 迁移不会丢失 Thread draft。
  const composer = (
    <Composer
      text={interaction.draft}
      onTextChange={interaction.updateDraft}
      contextReferences={interaction.contextReferences}
      onContextReferencesChange={interaction.updateContextReferences}
      threadId={conversation.currentThreadId}
      workspaceId={workspace.workspace?.workspaceId}
      runtimeGeneration={runtimeState?.generation}
      skills={composerSkills}
      slashCommands={[...goalPlanSlashCommands, ...(slashCommands ?? [])]}
      onSearchWorkspacePaths={searchWorkspacePaths}
      preferences={interaction.preferences}
      models={interaction.models}
      attachments={interaction.attachments}
      attachmentDraftItems={interaction.attachmentDraftItems}
      activeTurn={interaction.activeTurn}
      suspendedTurn={interaction.suspendedTurn}
      disabled={interaction.disabled}
      sending={interaction.sending}
      draftRecoveryRevision={interaction.draftRecoveryRevision}
      preferenceBusy={interaction.preferenceBusy}
      importingAttachments={interaction.importingAttachments}
      cancelling={interaction.cancelling}
      resuming={interaction.resuming}
      error={interaction.error}
      queuedInputs={interaction.queuedInputs}
      queueAccepting={interaction.queueAccepting}
      contextUsage={contextUsage}
      placeholder={
        interaction.preferences?.collaborationMode === "plan"
          ? "描述需要制定计划的任务…"
          : "随心输入"
      }
      modeStatus={
        planGoalAvailable && interaction.preferences !== undefined ? (
          <ComposerGoalStatus
            mode={interaction.preferences.collaborationMode}
            goal={goal.model?.goal}
            currentStepTitle={
              goal.model?.plan?.steps.find((step) => step.stepId === goal.model?.goal.currentStepId)
                ?.title
            }
            busy={interaction.preferenceBusy}
            onOpenGoal={onOpenGoal}
            onDisablePlan={() => interaction.changeCollaborationMode("default")}
          />
        ) : undefined
      }
      goalStatus={
        planGoalAvailable && goal.model !== undefined ? (
          <GoalStatusBar
            goal={goal.model.goal}
            evaluation={goal.model.evaluation}
            currentStepTitle={
              goal.model.plan?.steps.find((step) => step.stepId === goal.model?.goal.currentStepId)
                ?.title
            }
            busy={goal.busyAction !== undefined}
            onOpen={onOpenGoal}
            onPause={() => void goal.pause()}
            onResume={() => void goal.resume()}
            onResolve={onOpenGoal}
            onContinue={() => void goal.resume()}
          />
        ) : undefined
      }
      onModelChange={(value) => void interaction.changeModel(value)}
      onReasoningChange={(value) => void interaction.changeReasoning(value)}
      onAccessModeChange={(value) => void interaction.changeAccessMode(value)}
      onRestoreDefaults={
        settings.snapshot.defaultSelection === null
          ? undefined
          : () =>
              void interaction.resetPreferences(
                settings.snapshot.defaultSelection!,
                settings.snapshot.defaultAccessMode,
              )
      }
      onAddAttachments={attachmentPort === undefined ? undefined : interaction.importAttachments}
      onRetryAttachment={attachmentPort === undefined ? undefined : interaction.retryAttachment}
      onRemoveAttachment={attachmentPort === undefined ? undefined : interaction.removeAttachment}
      onPasteAttachments={attachmentPort === undefined ? undefined : interaction.importClipboard}
      onDropAttachments={
        attachmentPort === undefined ? undefined : interaction.importDroppedAttachments
      }
      nativeDropEvent={nativeDropEvent}
      dropZoneRef={onRegisterComposerDropZone}
      onOpenAttachmentPreview={(attachment, source) => {
        if (
          workspace.workspace === undefined ||
          (attachment.mediaKind !== "image" && attachment.mediaKind !== "text")
        )
          return;
        onOpenAttachmentPreview?.(
          {
            attachmentId: attachment.attachmentId,
            displayName: attachment.fileName,
            mediaKind: attachment.mediaKind,
            authorization: { kind: "draft" },
          },
          source,
        );
      }}
      onOpenWorkspaceReference={onOpenWorkspaceReference}
      onOpenQueuedAttachmentPreview={(attachment, source) => {
        if (
          threadId === "" ||
          (attachment.mediaKind !== "image" && attachment.mediaKind !== "text")
        )
          return;
        onOpenAttachmentPreview?.(
          {
            attachmentId: attachment.attachmentId,
            displayName: attachment.fileName,
            mediaKind: attachment.mediaKind,
            authorization: { kind: "thread", threadId },
          },
          source,
        );
      }}
      onSend={interaction.send}
      onEnqueue={interaction.enqueue}
      onPrioritizeQueuedInput={interaction.prioritizeQueuedInput}
      onUpdateQueuedInput={interaction.updateQueuedInput}
      onDeleteQueuedInput={interaction.deleteQueuedInput}
      onResume={interaction.resume}
      onCancel={interaction.cancel}
    />
  );
  const composerDock = (
    <div className="ja-conversation-composer-dock ja-conversation-content-rail">{composer}</div>
  );

  return (
    <section className="ja-conversation" aria-label="coding 对话">
      <h1 className="ja-visually-hidden">Ja 对话</h1>
      <header className="ja-conversation-header" aria-label="对话操作">
        <div className="ja-conversation-heading" title={threadTitle}>
          <FolderOpen aria-hidden="true" />
          <strong>{threadTitle}</strong>
        </div>
        <div className="ja-conversation-header-actions">
          <ReplyFileOpenMenu {...replyFileOpen} />
          <ConversationSummaryPopover summary={summary} />
          <ThreadOperationsMenu
            showCompactAction={conversation.canCompact}
            compaction={conversation.compaction}
            onCompact={conversation.compact}
            onDismissFeedback={conversation.dismissCompactionFeedback}
          />
          {inspectorOpen ? null : (
            <IconButton
              className="ja-inline-icon-button ja-conversation-drawer-toggle"
              label="显示工作区面板"
              onClick={onToggleInspector}
            >
              <RightPanelIcon />
            </IconButton>
          )}
        </div>
      </header>
      {scopeError === undefined ? null : (
        <p className="ja-inline-error" role="alert">
          {scopeError}
        </p>
      )}
      {conversation.error === undefined ? null : (
        <p className="ja-inline-error" role="alert">
          {conversation.error}
        </p>
      )}
      {!hasConversationContent ? (
        <section className="ja-conversation-empty" aria-labelledby="ja-conversation-empty-title">
          <div className="ja-conversation-empty-greeting">
            <img
              className="ja-conversation-empty-icon"
              src="/favicon.png"
              alt=""
              aria-hidden="true"
            />
            <h2 id="ja-conversation-empty-title">
              {isProjectScope ? (
                <>
                  你想在 <span>{scopeName}</span> 中构建什么？
                </>
              ) : (
                "你想让 Ja 帮你完成什么？"
              )}
            </h2>
          </div>
          {composerDock}
        </section>
      ) : (
        <ChatTimeline
          items={items}
          skills={composerSkills}
          turns={turns as Turn[]}
          approvals={approvals}
          approvalDecisions={approvalDecisions}
          approvalClosedAt={approvalClosedAt}
          localSubmissions={interaction.localSubmissions}
          attachmentThumbnailPort={attachmentPreviewPort}
          onApprovalDecision={(approval, decision) => void interaction.approve(approval, decision)}
          onPrepareRetry={(_turnId, text) => interaction.updateDraft(text)}
          onOpenLink={onOpenLink}
          onCopyText={onCopyText}
          onOpenAttachmentPreview={
            onOpenAttachmentPreview === undefined
              ? undefined
              : (attachment, source) =>
                  onOpenAttachmentPreview(
                    {
                      attachmentId: attachment.attachmentId,
                      displayName: attachment.displayName,
                      mediaKind: attachment.mediaKind,
                      authorization: attachment.authorization,
                    },
                    source,
                  )
          }
          onReadToolArtifact={
            artifactPort === undefined || workspace.workspace === undefined
              ? undefined
              : ({ threadId, turnId, callId, artifactId }) =>
                  artifactPort.readToolArtifact({
                    workspaceId: workspace.workspace!.workspaceId,
                    threadId,
                    turnId,
                    callId,
                    artifactId,
                  })
          }
          onReviewTurn={openFrozenTurnReview}
          externalRows={[...taskTimelineRows, ...planTimelineRows, ...goalTimelineRows]}
        />
      )}
      {interaction.sending ? (
        <span className="ja-visually-hidden" role="status" aria-live="polite">
          正在提交当前请求…
        </span>
      ) : null}
      <span className="ja-visually-hidden" role="status" aria-live="polite" aria-atomic="true">
        {interaction.clipboardNotice ?? ""}
      </span>
      {hasConversationContent ? composerDock : null}
    </section>
  );
}
