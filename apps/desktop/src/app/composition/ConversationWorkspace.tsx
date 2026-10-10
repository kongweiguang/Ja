// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { FolderOpen } from "lucide-react";
import { Fragment, useCallback, useEffect, useMemo, useState, type ReactElement } from "react";
import { toast } from "sonner";
import { Notification } from "@/shared/ui/notifications";
import { Button } from "@/shared/ui/primitives";
import { useShallow } from "zustand/react/shallow";
import {
  TimelineDisclosureCache,
  TimelineScrollCache,
  Composer,
  InteractionCard,
  useInteractionController,
  type InteractionPort,
  ConversationSummaryPopover,
  ConversationTimelineSurface,
  countCommittedConversationActivity,
  itemChangedFiles,
  itemDiffStat,
  ReplyFileOpenMenu,
  ThreadOperationsMenu,
  selectApprovalClosedAtForThread,
  selectApprovalDecisionsForThread,
  selectApprovalsForThread,
  selectCommittedItemsForThread,
  selectTurnsForThread,
  turnDurationMs,
  turnStatusLabel,
  resolveContextUsage,
  useConversationInteractionController,
  useReplyFileOpen,
  useTimelineStore,
  type ConversationController,
  type ConversationProcessBinding,
  type ConversationAttachmentPort,
  type ConversationArtifactPort,
  type ConversationContextReference,
  type ConversationSummary,
  type ConversationMcpReader,
  type ConversationUsageReader,
  type ComposerSlashCommand,
  type TimelineTurn as Turn,
} from "@/features/conversation";
import type { SettingsController } from "@/features/settings";
import type {
  AttachmentPreviewPort,
  AttachmentPreviewTarget,
  PreviewTarget,
} from "@/features/workbench/preview";
import type { TurnReviewTarget } from "@/features/workbench/review";
import type { WorkspaceController } from "@/features/workspace";
import type { NativeDropEvent } from "@/api/tauri/nativeDrop";
import { TaskActivityCard, useTaskActivityTimeline, type TaskSummary } from "@/features/tasks";
import {
  ComposerPlanStatus,
  PlanTimelineBlock,
  PlanStatusBar,
  type PlanController,
} from "@/features/plans";
import { RightPanelIcon } from "@/shared/ui/RightPanelIcon";
import type { JaWorkbenchAdapters } from "../useJaWorkbench";
import { useRuntimeLifecycle, useRuntimeState, useRuntimeTurns } from "../RuntimeProvider";
import { IconButton } from "@/shared/ui/primitives";
import { modelSelectionId } from "@/shared/settings/types";
import { useLatestTurnReviewPublisher } from "../application/useLatestTurnReviewPublisher";
import { conversationModeCommands } from "../application/conversationModeCommands";
import { PendingOperationNotification } from "./PendingOperationNotification";

export interface ConversationWorkspaceProps {
  readonly workspace: WorkspaceController;
  readonly conversation: ConversationController;
  /** Host visibility gates retained Composer IO and high-frequency Timeline subscriptions. */
  readonly active?: boolean;
  /** 编辑项目 Modal 覆盖工作面时暂停会话提示，避免门户覆盖对话框并保留各 owner 身份。 */
  readonly projectEditorOpen?: boolean;
  /** Composer 只消费 Thread 级只读账本，历史 adapter 与刷新时序仍留在 composition。 */
  readonly usageReader?: ConversationUsageReader;
  readonly settings: SettingsController;
  readonly inspectorOpen: boolean;
  readonly onToggleInspector: () => void;
  readonly summaryContext: Pick<ConversationSummary, "scope" | "gitBranch" | "runtime">;
  readonly mcpReader?: ConversationMcpReader;
  readonly onOpenMcpSettings?: () => void;
  readonly onOpenModelSettings?: () => void;
  readonly onEditProject?: (workspaceId: string) => void | Promise<void>;
  readonly workspaceAdapter: JaWorkbenchAdapters["workspace"];
  readonly onOpenLink: (url: string, source?: HTMLElement) => void | Promise<void>;
  readonly onOpenFile: (
    target: { path: string; line?: number; column?: number },
    source: HTMLElement,
    mode?: "explorer" | "browser",
  ) => void | Promise<void>;
  readonly onCopyText: (text: string) => Promise<void>;
  readonly onExportConversation?: () => Promise<
    "saved" | "cancelled" | "unsupported" | "downloaded"
  >;
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
  readonly processSession?: ConversationProcessBinding;
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
  readonly planAvailable: boolean;
  readonly plan: PlanController;
  readonly interactionPort?: InteractionPort;
  readonly onOpenPlan: () => void;
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

/** 文件链接的单次 intent 同时携带 Thread/Workspace identity，Explorer 不占用右栏页面。 */
export interface ConversationOpenTargetRequest {
  readonly requestId: number;
  readonly workspaceId: string;
  readonly threadId: string;
  /** 同一项目 ID 换目录后也使旧点击失效，不能只比较 Workspace 和 Thread。 */
  readonly bindingRevision?: string;
  readonly target:
    | PreviewTarget
    | { kind: "explorer"; path: string }
    | { kind: "preview"; path: string; line?: number; column?: number };
}

/** Host 仅结算实际打开结果；失败由来源消息保留并恢复键盘焦点。 */
export type ConversationOpenTargetOutcome = "opened" | "failed";

/** Workbench 只回传稳定结果，原生读取错误细节继续由 Files 的脱敏通知边界拥有。 */
export type WorkspaceReferencePreviewOutcome = "opened" | "failed" | "closed";

/**
 * 组合 Conversation、Workspace、Settings 与窄 Runtime 端口；高频 Timeline 只在可见边界订阅 owner slice。
 * 启动、恢复和操作反馈仍由各自 owner 负责，避免 App 壳重复持有状态或改变消息布局。
 * @author kongweiguang
 */
export function ConversationWorkspace({
  workspace,
  conversation,
  active = true,
  projectEditorOpen = false,
  usageReader,
  settings,
  inspectorOpen,
  onToggleInspector,
  summaryContext,
  mcpReader,
  onOpenMcpSettings,
  onOpenModelSettings,
  workspaceAdapter,
  onOpenLink,
  onOpenFile,
  onEditProject,
  onCopyText,
  onExportConversation,
  onConversationFocusAvailabilityChange,
  onRegisterWorkspaceReferenceTarget,
  onOpenWorkspaceReference,
  attachmentPort,
  attachmentPreviewPort,
  artifactPort,
  processSession,
  nativeDropEvent,
  onRegisterComposerDropZone,
  onOpenAttachmentPreview,
  onLatestTurnReviewChange,
  onOpenTurnReview,
  onAttachmentRemoved,
  onAttachmentsBound,
  slashCommands,
  onOpenTask,
  planAvailable,
  plan,
  interactionPort,
  onOpenPlan,
}: ConversationWorkspaceProps): ReactElement {
  const { boot, turnAdmissionReady, runtimeState, pendingClientOperations } = useRuntimeState();
  const { queryRuntime } = useRuntimeLifecycle();
  const turnPort = useRuntimeTurns();
  // 后台恢复仍在自动重试时只呈现状态；同一时刻优先保留更可操作的前台错误。
  const visibleConversationError = conversation.error;
  const visibleRecoveryStatus =
    conversation.error === undefined ? conversation.backgroundError : undefined;
  const threadId = conversation.currentThreadId ?? "";
  const noticeScope = `${workspace.workspace?.workspaceId ?? ""}:${threadId}`;
  const workspaceNoticeScope = `${workspace.workspace?.workspaceId ?? ""}:directory-unavailable`;
  const [unavailableNoticeWorkspaceId, setUnavailableNoticeWorkspaceId] = useState<
    string | undefined
  >(undefined);
  const currentWorkspaceId = workspace.workspace?.workspaceId;
  const projectWorkspaceId =
    workspace.workspace?.kind === "project" ? workspace.workspace.workspaceId : undefined;
  // checking 保留上一不可用 owner；受控 props 改变时在当前组件内同步重算，
  // 避免 effect 提交一帧旧提示后再更新，也保留原 toast 的重试与退出动画。
  const nextUnavailableNoticeWorkspaceId =
    projectWorkspaceId !== undefined && workspace.directoryState === "unavailable"
      ? projectWorkspaceId
      : workspace.directoryState === "available" ||
          currentWorkspaceId !== unavailableNoticeWorkspaceId
        ? undefined
        : unavailableNoticeWorkspaceId;
  if (nextUnavailableNoticeWorkspaceId !== unavailableNoticeWorkspaceId) {
    setUnavailableNoticeWorkspaceId(nextUnavailableNoticeWorkspaceId);
  }
  const showUnavailableNotice =
    workspace.workspace?.kind === "project" &&
    (workspace.directoryState === "unavailable" ||
      (workspace.directoryState === "checking" &&
        workspace.workspace.workspaceId === unavailableNoticeWorkspaceId));
  const notificationsEnabled = active && !projectEditorOpen;
  const timelineScrollCache = useMemo(() => new TimelineScrollCache(), []);
  const timelineDisclosureCache = useMemo(() => new TimelineDisclosureCache(), []);
  const [composerFocusRequest, setComposerFocusRequest] = useState(0);
  /** ConversationWorkspace 统一释放滚动和折叠快照，避免项目切换后复用旧 Thread 的瞬态 UI 状态。 */
  useEffect(
    () => () => {
      timelineScrollCache.clear();
      timelineDisclosureCache.clear();
    },
    [timelineDisclosureCache, timelineScrollCache],
  );
  const clarification = useInteractionController({
    threadId: conversation.currentThreadId,
    // Runtime 恢复/重连只撤销发送准入，不能卸载 Interaction 快照；否则待回答卡会在恢复轮次中闪退再出现。
    visible: runtimeState?.features.includes("interaction") === true,
    port: interactionPort,
  });
  /** 运行中修改先等待原 Run 暂停，再聚焦 Composer，不能让新需求偷偷替换已授权版本。 */
  const modifyPlan = useCallback(() => {
    const status = plan.planModel?.plan.status;
    if (status === "executing" || status === "verifying") {
      void plan.pausePlan().then((paused) => {
        if (paused) setComposerFocusRequest((value) => value + 1);
      });
    } else setComposerFocusRequest((value) => value + 1);
  }, [plan]);
  const taskActivities = useTaskActivityTimeline(
    threadId === "" ? undefined : threadId,
    runtimeState?.generation,
    active,
  );
  /**
   * Task Activity 只映射持久摘要与稳定 identity；Child Transcript 不进入父 Conversation reducer，
   * 统一 Timeline 的排序与虚拟化由 ChatTimeline 在展示边界完成。
   */
  const taskTimelineRows = useMemo(
    () =>
      taskActivities.map(({ activity, task, firstOccurredAt }) => ({
        // Task 状态在原位更新；row identity 与位置锚定任务，而不是最新 Activity。
        rowId: task.taskThreadId,
        occurredAt: firstOccurredAt,
        revision: `${activity.activitySequence}:${task.revision}`,
        content: <TaskActivityCard activity={activity} task={task} onOpen={onOpenTask} />,
      })),
    [onOpenTask, taskActivities],
  );
  /**
   * 当前冻结 Plan 作为 Timeline 的结构化事实展示；详情入口显式打开 capability，组件本身不让
   * PlanWorkbench 常驻，也不从 agent Markdown 推导步骤或验收状态。
   */
  const planTimelineRows = useMemo(() => {
    const planModel = plan.planModel;
    if (planModel === undefined || planModel.plan.ownerThreadId !== threadId) return [];
    return [
      {
        rowId: `plan:${planModel.plan.planId}:${planModel.revision?.planRevisionId ?? "draft"}`,
        occurredAt: planModel.revision?.createdAt ?? planModel.plan.createdAt,
        revision: `${planModel.plan.revision}:${planModel.eventSequence}`,
        content: (
          <PlanTimelineBlock
            planModel={planModel}
            onOpenDetails={onOpenPlan}
            onModifyPlan={modifyPlan}
            onExecute={() => void plan.execute()}
            busy={plan.busyAction !== undefined}
          />
        ),
      },
    ];
  }, [plan, onOpenPlan, modifyPlan, threadId]);
  const turns = useTimelineStore(
    useShallow((state) => (threadId === "" ? [] : selectTurnsForThread(threadId)(state))),
  );
  const committedItems = useTimelineStore(
    useShallow((state) => (threadId === "" ? [] : selectCommittedItemsForThread(threadId)(state))),
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
  const currentPlanId = plan.planModel?.plan.planId;
  const latestTerminalPlanTurnId = latestTerminalPlanTurn?.turnId;
  const refreshPlan = plan.refresh;
  /**
   * Plan mode Turn 到达终态后显式回读 Plan 聚合，
   * 让 agent 提出的 revision/approval 状态进入 UI，同时不在流式阶段轮询或扫描 Workspace。
   */
  useEffect(() => {
    if (currentPlanId === undefined || latestTerminalPlanTurnId === undefined) return;
    void refreshPlan();
  }, [currentPlanId, latestTerminalPlanTurnId, refreshPlan]);
  const latestTurnReview = useMemo<TurnReviewTarget | undefined>(() => {
    const workspaceId = workspace.workspace?.workspaceId;
    if (workspaceId === undefined) return undefined;
    // 对象插入顺序遵循已提交的 Timeline 序号；只取最后一个终态 Turn，
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
  const currentThread = conversation.threads.find(
    (candidate) => candidate.threadId === conversation.currentThreadId,
  );
  /**
   * 摘要只归约已提交的 Timeline 事实；缺失指标继续缺失，不能用零伪造证据，也不能让
   * 逐段 Draft 为了头部 Popover 重新渲染 Composer 与 Navigation 的共同布局。
   */
  const summary = useMemo<ConversationSummary>(() => {
    const changedFileMetrics = committedItems
      .map(itemChangedFiles)
      .filter((value): value is number => value !== undefined);
    const diffMetrics = committedItems
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
    const selectedModel = settings.snapshot.providers
      .find((provider) => provider.providerId === currentThread?.preferences?.providerId)
      ?.models.find((model) => model.modelId === currentThread?.preferences?.modelId);
    return {
      ...summaryContext,
      model: selectedModel?.model,
      status: activeStatus === undefined ? undefined : turnStatusLabel(activeStatus),
      turnCount: turns.length,
      activity: countCommittedConversationActivity(committedItems),
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
  }, [
    committedItems,
    currentThread?.preferences,
    settings.snapshot.providers,
    summaryContext,
    turns,
  ]);
  const contextFacts = useTimelineStore(
    useShallow((state) => {
      if (threadId === "") return { usage: undefined, compaction: undefined };
      const usage = state.contextUsageByThread[threadId];
      return {
        usage,
        invalidatedAt: state.contextUsageInvalidatedAtByThread[threadId],
        compaction: state.contextCompactionByThread[threadId],
      };
    }),
  );
  const approvals = useTimelineStore(
    useShallow((state) => (threadId === "" ? [] : selectApprovalsForThread(threadId)(state))),
  );
  const approvalDecisions = useTimelineStore(
    useShallow((state) =>
      threadId === "" ? {} : selectApprovalDecisionsForThread(threadId)(state),
    ),
  );
  const approvalClosedAt = useTimelineStore(
    useShallow((state) =>
      threadId === "" ? {} : selectApprovalClosedAtForThread(threadId)(state),
    ),
  );
  const isProjectScope = workspace.workspace?.kind === "project";
  const replyFileOpen = useReplyFileOpen(
    isProjectScope ? workspace.workspace?.workspaceId : undefined,
    committedItems,
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
    invalidatedAt: contextFacts.invalidatedAt,
    compaction: contextFacts.compaction,
  });
  /** 只由请求身份或结算/恢复事实变化推进，避免普通渲染与关闭浮层制造后台读取。 */
  const usageRefreshRevision = [
    contextFacts.usage?.modelRequestId,
    contextFacts.usage?.modelRequestOrdinal,
    contextFacts.usage?.modelRound,
    contextFacts.usage?.measuredAt,
    contextFacts.invalidatedAt,
    contextFacts.compaction?.phase,
    contextFacts.compaction?.occurredAt,
  ].join("\u0000");
  // Workspace commit 与配置 Query key 切换可能跨一个 React effect；只有目标 scope 已取得
  // 权威快照才开放 Composer，防止旧项目 Provider 配置进入新项目 Turn。
  const settingsScopeReady =
    settings.scopeReady &&
    settings.scopeWorkspaceId ===
      (workspace.workspace?.kind === "project" ? workspace.workspace.workspaceId : undefined);
  const ready =
    settingsScopeReady &&
    turnAdmissionReady &&
    (workspace.workspace === undefined || workspace.bindingReady) &&
    (boot.status === "ready" || boot.status === "busy");
  /** 一次性取消失败使用全局 Portal 提示，避免在 Composer 下面留下不会自动消失的红字。 */
  const reportConversationTransientError = useCallback((message: string): void => {
    toast.error(message, { id: "ja-conversation-cancel-failed" });
  }, []);
  /**
   * 原 Tool 详情的裁决在离开当前 Thread、Turn 不再暂停或 revision 已变时失败关闭；真正的幂等、
   * 调用归属与自动续跑继续由 Runtime/App Server 所有，视图不在 ACK 后伪造任何 Tool 结果。
   */
  const resolveToolRecovery = useCallback(
    async (input: {
      threadId: string;
      turnId: string;
      callId: string;
      expectedThreadRevision: number;
      expectedRecoveryRevision: number;
      decision: "retry" | "skip";
      operationKey: string;
    }): Promise<void> => {
      const matchingTurn = turns.find((turn) => turn.turnId === input.turnId);
      if (
        input.threadId !== threadId ||
        matchingTurn?.threadId !== input.threadId ||
        matchingTurn.status !== "suspended" ||
        matchingTurn.threadRevision !== input.expectedThreadRevision
      ) {
        throw new Error("tool recovery state is stale");
      }
      await turnPort.respondToolRecovery(input);
    },
    [threadId, turnPort, turns],
  );
  const interaction = useConversationInteractionController({
    threadId: conversation.currentThreadId,
    workspaceId: workspace.workspace?.workspaceId,
    preferences: currentThread?.preferences ?? undefined,
    models,
    ready,
    blocked: workspace.busy || conversation.busy,
    turnPort,
    readThreadRevision: conversation.readThreadRevision,
    planCreationPort: planAvailable
      ? {
          // 模式切换 ACK 可能先于 Timeline 快照；采用同 Thread 已确认的最高 revision 创建计划。
          create: (ownerThreadId, objective, revision) =>
            plan.createPlan(
              ownerThreadId,
              objective,
              Math.max(
                revision,
                currentThread?.threadId === ownerThreadId ? currentThread.revision : revision,
              ),
            ),
        }
      : undefined,
    preferencesPort: { updatePreferences: conversation.updatePreferences },
    attachmentPort,
    onAttachmentRemoved,
    onAttachmentsBound,
    onTransientError: reportConversationTransientError,
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
   * Plan slash action 只修改协作模板，不读写 AccessMode，也不把命令文本提交给模型。
   */
  const planSlashCommands = conversationModeCommands({
    available: planAvailable,
    interaction,
  });
  const hasConversationContent =
    committedItems.length > 0 ||
    turns.length > 0 ||
    approvals.length > 0 ||
    interaction.localSubmissions.length > 0 ||
    taskActivities.length > 0 ||
    planTimelineRows.length > 0;
  const scopeName = isProjectScope
    ? (workspace.workspace?.displayName ?? "未命名项目")
    : "无项目对话";
  const threadTitle = currentThread?.title.trim() || scopeName;
  // 生命周期故障统一在侧栏解释，避免同一原因再次占据对话正文。
  const scopeError =
    boot.status === "ready" || boot.status === "busy" ? workspace.error : undefined;
  const pendingOperationForThread = pendingClientOperations.find(
    (record) => record.threadId === null || record.threadId === conversation.currentThreadId,
  );

  /** Composer 可用性只作为壳层快捷键 capability 发布，卸载时立即撤销。 */
  useEffect(() => {
    onConversationFocusAvailabilityChange(active && !interaction.disabled);
    return () => onConversationFocusAvailabilityChange(false);
  }, [active, interaction.disabled, onConversationFocusAvailabilityChange]);

  const forkConversationReply = conversation.forkReply;
  /** 打开新 Thread 后聚焦目标 Composer；依赖稳定用例函数，保留来源草稿而不订阅整个控制器对象。 @author kongweiguang */
  const forkReply = useCallback(
    async (anchor: { threadId: string; itemId: string }) => {
      const outcome = await forkConversationReply(anchor);
      if (outcome === "opened" || outcome === "open_failed")
        setComposerFocusRequest((value) => value + 1);
      return outcome;
    },
    [forkConversationReply],
  );

  // 空 Thread 与有内容 Thread 共用同一受控 Composer，视觉 dock 迁移不会丢失 Thread draft。
  const composer = (
    <Composer
      active={active}
      focusRequest={composerFocusRequest}
      interactionSlot={<InteractionCard controller={clarification} />}
      text={interaction.draft}
      onTextChange={interaction.updateDraft}
      contextReferences={interaction.contextReferences}
      onContextReferencesChange={interaction.updateContextReferences}
      threadId={conversation.currentThreadId}
      workspaceId={workspace.workspace?.workspaceId}
      runtimeGeneration={runtimeState?.generation}
      skills={composerSkills}
      slashCommands={[...planSlashCommands, ...(slashCommands ?? [])]}
      onSearchWorkspacePaths={searchWorkspacePaths}
      preferences={interaction.preferences}
      models={interaction.models}
      modelSettingsRequired={interaction.modelSettingsRequired}
      onOpenModelSettings={onOpenModelSettings}
      attachments={interaction.attachments}
      attachmentDraftItems={interaction.attachmentDraftItems}
      attachmentThumbnailPort={attachmentPreviewPort}
      activeTurn={interaction.activeTurn}
      suspendedTurn={interaction.suspendedTurn}
      continuationAvailable={interaction.continuationAvailable}
      editingQuestion={interaction.editingQuestion}
      onCancelEdit={interaction.cancelEditQuestion}
      interactionPresentation={
        clarification.request?.status !== "pending"
          ? "none"
          : clarification.collapsed
            ? "collapsed"
            : "expanded"
      }
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
      usageReader={usageReader}
      usageRefreshRevision={usageRefreshRevision}
      placeholder={
        interaction.preferences?.collaborationMode === "plan"
          ? "描述需要制定计划的任务…"
          : "随心输入"
      }
      modeStatus={
        planAvailable && interaction.preferences !== undefined ? (
          <ComposerPlanStatus
            mode={interaction.preferences.collaborationMode}
            busy={interaction.preferenceBusy}
            onOpenPlan={plan.planModel === undefined ? undefined : onOpenPlan}
            onDisablePlan={() => interaction.changeCollaborationMode("default")}
          />
        ) : undefined
      }
      planStatus={
        planAvailable &&
        plan.planModel !== undefined &&
        plan.planModel.plan.activeRunId !== null ? (
          <PlanStatusBar
            planModel={plan.planModel}
            busy={plan.busyAction !== undefined}
            onOpen={onOpenPlan}
            onPause={() => void plan.pausePlan()}
            onResume={() => void plan.resumePlan()}
            onStop={() => void plan.stopPlan()}
            hasPendingQuestion={clarification.request?.status === "pending"}
            onAnswerQuestion={() => clarification.setCollapsed(false)}
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
      nativeDropEvent={active ? nativeDropEvent : undefined}
      dropZoneRef={active ? onRegisterComposerDropZone : undefined}
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
      onContinue={interaction.continueReply}
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
          <ConversationSummaryPopover
            summary={summary}
            threadId={threadId}
            threadTitle={threadTitle}
            createdAt={currentThread?.createdAt}
            rootPath={workspace.workspace?.rootPath}
            onCopyText={onCopyText}
            mcpReader={mcpReader}
            onOpenMcpSettings={onOpenMcpSettings}
          />
          <ThreadOperationsMenu
            key={noticeScope}
            scope={noticeScope}
            enabled={notificationsEnabled}
            showCompactAction={conversation.canCompact}
            showExportAction={
              onExportConversation !== undefined && (committedItems.length > 0 || turns.length > 0)
            }
            compaction={conversation.compaction}
            onCompact={conversation.compact}
            onCancelCompaction={conversation.cancelCompaction}
            onDismissFeedback={conversation.dismissCompactionFeedback}
            onExportConversation={onExportConversation}
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
      <div
        className={`ja-conversation-body${hasConversationContent ? "" : " ja-conversation-empty"}`}
        data-state={hasConversationContent ? "content" : "empty"}
      >
        <Notification
          scope={workspaceNoticeScope}
          message={workspace.error?.includes("已保存") ? workspace.error : "项目目录不可用"}
          tone="warning"
          dismissible={false}
          enabled={notificationsEnabled && showUnavailableNotice}
        >
          <Button
            type="button"
            disabled={
              workspace.busy ||
              workspace.directoryState === "checking" ||
              workspace.workspace?.kind !== "project"
            }
            onClick={() => {
              if (workspace.workspace?.kind === "project")
                void onEditProject?.(workspace.workspace.workspaceId);
            }}
          >
            修改目录
          </Button>
          <Button
            type="button"
            disabled={
              workspace.busy ||
              workspace.directoryState === "checking" ||
              workspace.workspace?.kind !== "project"
            }
            onClick={() => void workspace.retryBinding()}
          >
            重新检查
          </Button>
        </Notification>
        {active ? (
          <Fragment key={noticeScope}>
            {showUnavailableNotice ? null : scopeError === undefined ? null : (
              <Notification
                scope={noticeScope}
                message={scopeError}
                tone="error"
                enabled={notificationsEnabled}
              />
            )}
            <PendingOperationNotification
              scope={noticeScope}
              pending={pendingOperationForThread}
              enabled={notificationsEnabled}
            />
            {visibleRecoveryStatus === undefined ? null : (
              <Notification
                scope={noticeScope}
                message={visibleRecoveryStatus}
                dismissible={false}
                enabled={notificationsEnabled}
              />
            )}
            {visibleConversationError === undefined ? null : (
              <Notification
                scope={noticeScope}
                message={visibleConversationError.message}
                tone="error"
                enabled={notificationsEnabled}
              >
                {visibleConversationError.source === "history-load" ? (
                  <button type="button" onClick={() => void conversation.retryHistory()}>
                    重试
                  </button>
                ) : undefined}
              </Notification>
            )}
          </Fragment>
        ) : null}
        {!hasConversationContent ? (
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
        ) : (
          <ConversationTimelineSurface
            active={active}
            threadId={threadId === "" ? undefined : threadId}
            hasOlderHistory={conversation.hasOlderHistory}
            loadingOlderHistory={conversation.loadingOlderHistory}
            olderHistoryError={conversation.olderHistoryError}
            onLoadOlderHistory={conversation.loadOlderHistory}
            scrollCache={timelineScrollCache}
            disclosureCache={timelineDisclosureCache}
            answeredRequest={clarification.answeredRequest}
            answeredAnswers={clarification.answeredAnswers}
            skills={composerSkills}
            turns={turns as Turn[]}
            onForkReply={forkReply}
            forkingReplies={conversation.forkingReplies}
            forkCreatedReplies={conversation.forkCreatedReplies}
            forkOpenFailures={conversation.forkOpenFailures}
            forkConflictReplies={conversation.forkConflictReplies}
            forkUnconfirmedReplies={conversation.forkUnconfirmedReplies}
            approvals={approvals}
            approvalDecisions={approvalDecisions}
            approvalClosedAt={approvalClosedAt}
            localSubmissions={interaction.localSubmissions}
            attachmentThumbnailPort={attachmentPreviewPort}
            onApprovalDecision={(approval, decision) =>
              void interaction.approve(approval, decision)
            }
            onResolveToolRecovery={resolveToolRecovery}
            editableSourceMessageId={interaction.editableSourceMessageId}
            onEditQuestion={interaction.editQuestion}
            onOpenLink={onOpenLink}
            onOpenFile={onOpenFile}
            onCopyText={onCopyText}
            onReadMessageContent={conversation.readMessageContent}
            onReadAnswerContent={conversation.readAnswerContent}
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
            processSession={processSession}
            onReviewTurn={openFrozenTurnReview}
            externalRows={[...taskTimelineRows, ...planTimelineRows]}
          />
        )}
      </div>
      {/* Composer 始终位于同一父级和同一兄弟顺序；空态只切换 greeting，避免输入器 remount。 */}
      {composerDock}
      <div className="ja-visually-hidden">
        {interaction.sending ? (
          <span role="status" aria-live="polite">
            正在提交当前请求…
          </span>
        ) : null}
        <span role="status" aria-live="polite" aria-atomic="true">
          {interaction.clipboardNotice ?? ""}
        </span>
      </div>
    </section>
  );
}
