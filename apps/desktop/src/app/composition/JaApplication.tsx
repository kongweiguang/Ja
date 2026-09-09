// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactElement,
} from "react";
import { toast } from "sonner";
import { useRuntimeLifecycle, useRuntimeState } from "../RuntimeProvider";
import type { JaWorkbenchAdapters } from "../useJaWorkbench";
import { Button } from "@/shared/ui/primitives/Button";
import {
  useSettingsController,
  type DesktopNotificationPreference,
  type SettingsAdapter,
  type SettingsAppearancePort,
  type SettingsRuntimePort,
} from "@/features/settings";
import {
  CommandPalette,
  useCommandPaletteController,
  type CommandAction,
} from "@/features/command";
import {
  useConversationController,
  type ConversationArtifactPort,
  type ConversationAttachmentPort,
  type ComposerSlashCommand,
  type ConversationSummary,
} from "@/features/conversation";
import {
  AppTitlebar,
  ConversationSearchDialog,
  NavigationResizeHandle,
  NavigationSidebar,
  detectDesktopPlatform,
  navigationShortcut,
  useWindowFrameController,
  type NavigationCommand,
} from "@/features/navigation";
import {
  WorkbenchResizeHandle,
  taskWorkbenchTab,
  type WorkbenchCapability,
  type WorkbenchTabKey,
} from "@/features/workbench";
import { createTaskAdapter, type TaskAdapter } from "@/api/tauri/tasks";
import type {
  TaskComposerSkillSuggestion,
  TaskSummary,
  TaskThreadRenamePort,
  TaskTranscriptPort,
} from "@/features/tasks";
import type { AttachmentPreviewPort, AttachmentPreviewTarget } from "@/features/workbench/preview";
import type { TurnReviewPort, TurnReviewTarget } from "@/features/workbench/review";
import { useGoalController, type GoalPort } from "@/features/goals";
import type { HistoryAdapter } from "@/api/tauri/history";
import {
  SIDEBAR_RATIO_MAX,
  SIDEBAR_RATIO_MIN,
  WORKBENCH_SIZE_MAX,
  WORKBENCH_SIZE_MIN,
  useUiPreferencesStore,
} from "@/shared/preferences/uiPreferences";
import type { DesktopIntegrationAdapters } from "@/api/tauri/desktop";
import { observeAppExitRequested } from "@/api/tauri/window";
import { useDesktopNotifications } from "../useDesktopNotifications";
import {
  useWorkspaceController,
  type WorkspacePickerPort,
  type WorkspaceProjection,
} from "@/features/workspace";
import { useFilesCapabilityCloseController } from "../application/useFilesCapabilityCloseController";
import { useAppExitController } from "../application/useAppExitController";
import { useWorkspaceChangeController } from "../application/useWorkspaceChangeController";
import { usePageNavigationController } from "../application/usePageNavigationController";
import { useResponsiveShellController } from "../application/useResponsiveShellController";
import { useNativeNavigationShortcuts } from "../application/useNativeNavigationShortcuts";
import {
  canOpenTurnReview,
  decideInitialReviewSource,
  resolveRetainedTurnLabel,
} from "../application/reviewSourceSelection";
import {
  useComposerNativeDropRouter,
  type NativeDropSubscriptionPort,
} from "../application/useComposerNativeDropRouter";
import { RecoveryPanel } from "./RecoveryPanel";
import { SettingsView } from "./SettingsView";
import { ThreadWorkbenchSessions } from "./ThreadWorkbenchSessions";
import { useThreadWorkbenchState } from "../application/useThreadWorkbenchState";
import {
  ConversationWorkspace,
  type ComposerWorkspaceReferenceTarget,
  type WorkspaceReferencePreviewOutcome,
  type WorkspaceReferencePreviewRequest,
} from "./ConversationWorkspace";
import {
  DEFAULT_DESKTOP_INTEGRATIONS,
  DEFAULT_ATTACHMENT_PORT,
  DEFAULT_ATTACHMENT_PREVIEW_PORT,
  DEFAULT_CONVERSATION_ARTIFACT_PORT,
  DEFAULT_HISTORY_ADAPTER,
  DEFAULT_GOAL_PORT,
  DEFAULT_SETTINGS_ADAPTER,
  DEFAULT_TURN_REVIEW_PORT,
  DEFAULT_NATIVE_DROP_PORT,
  DEFAULT_WORKBENCH_ADAPTERS,
  DEFAULT_WORKSPACE_PICKER,
  projectSettingsConfigurationChange,
} from "./defaultAdapters";
import {
  DEFAULT_NAVIGATION_NATIVE_ADAPTERS,
  type NavigationNativeAdapters,
} from "./navigationAdapters";
import "../App.css";

/** 保持连接文案简短，壳层不暴露 native 诊断。 */
function runtimeLabel(status: ReturnType<typeof useRuntimeState>["boot"]["status"]): string {
  switch (status) {
    case "ready":
      return "已连接";
    case "busy":
      return "工作中";
    case "connecting":
      return "连接中";
    case "recovery_required":
      return "需要恢复";
    case "stopped":
      return "未启动";
    case "failed":
      return "连接失败";
    case "degraded":
      return "运行时受限";
    case "idle":
      return "等待设置";
  }
}

/** 把详细 lifecycle state 映射到 Navigation 拥有的五种视觉 tone。 */
function runtimeTone(
  status: ReturnType<typeof useRuntimeState>["boot"]["status"],
): "ready" | "busy" | "warning" | "danger" | "idle" {
  switch (status) {
    case "ready":
      return "ready";
    case "busy":
    case "connecting":
      return "busy";
    case "recovery_required":
    case "degraded":
      return "warning";
    case "failed":
      return "danger";
    case "stopped":
    case "idle":
      return "idle";
  }
}

/**
 * 在会话导航状态提交后查询当前 Composer 并聚焦；查询延后到下一帧，避免侧栏/Dialog 的
 * 焦点归还或 Thread keyed remount 把光标重新移走。
 */
function focusConversationComposerAfterNavigation(): void {
  /** 查询执行时的当前节点，避免 Thread 切换后把焦点交给已经卸载的旧 Composer。 */
  const focusComposer = (): void => {
    const composer = document.querySelector<HTMLTextAreaElement>('[aria-label="消息"]');
    if (composer?.isConnected && !composer.disabled) composer.focus();
  };
  if (typeof window.requestAnimationFrame === "function")
    window.requestAnimationFrame(focusComposer);
  else focusComposer();
}

/** 从 Workbench 返回时优先恢复对象来源；对象已卸载则回到当前 Composer，避免焦点丢进 body。 */
function restoreConversationObjectFocus(source: HTMLButtonElement | null): void {
  const restoreFocus = (): void => {
    if (source?.isConnected) {
      source.focus();
      return;
    }
    const composer = document.querySelector<HTMLTextAreaElement>('[aria-label="消息"]');
    if (composer?.isConnected && !composer.disabled) composer.focus();
  };
  if (typeof window.requestAnimationFrame === "function")
    window.requestAnimationFrame(restoreFocus);
  else restoreFocus();
}

export interface JaApplicationProps {
  readonly settingsAdapter?: SettingsAdapter;
  readonly projectPicker?: WorkspacePickerPort;
  readonly historyAdapter?: HistoryAdapter;
  readonly attachmentPort?: ConversationAttachmentPort;
  readonly attachmentPreviewPort?: AttachmentPreviewPort;
  readonly nativeDropPort?: NativeDropSubscriptionPort;
  readonly conversationArtifactPort?: ConversationArtifactPort;
  readonly turnReviewPort?: TurnReviewPort;
  readonly workbenchAdapters?: JaWorkbenchAdapters;
  readonly desktopAdapters?: DesktopIntegrationAdapters;
  readonly navigationAdapters?: NavigationNativeAdapters;
  readonly taskAdapter?: TaskAdapter;
  readonly goalPort?: GoalPort;
}

const APP_EXIT_OBSERVER = { observe: observeAppExitRequested };
const DEFAULT_TASK_ADAPTER = createTaskAdapter();

/**
 * 在 composition root 组合 native adapter 与 Files/Terminal 生命周期，不把资源状态提升到壳层；
 * 设置页只隐藏并暂停工作区而不卸载其子树，使配色热切换可保留 Editor/xterm 实例与 PTY 所有权。
 * 右栏目标和回调按 Thread 隔离，同项目切换保留各会话工作面，不将项目身份当作会话身份。
 * runtimeState 的正 generation 是 Review 事件的 admission fence，缺失时 Review 只读且不接受失效刷新。
 * 首屏始终挂载对话；运行时启动与故障只投影到侧栏，不改变发送准入或人工恢复门禁。
 */
export function JaApplication({
  settingsAdapter,
  projectPicker,
  historyAdapter,
  attachmentPort = DEFAULT_ATTACHMENT_PORT,
  attachmentPreviewPort = DEFAULT_ATTACHMENT_PREVIEW_PORT,
  nativeDropPort = DEFAULT_NATIVE_DROP_PORT,
  conversationArtifactPort = DEFAULT_CONVERSATION_ARTIFACT_PORT,
  turnReviewPort = DEFAULT_TURN_REVIEW_PORT,
  workbenchAdapters,
  desktopAdapters = DEFAULT_DESKTOP_INTEGRATIONS,
  navigationAdapters = DEFAULT_NAVIGATION_NATIVE_ADAPTERS,
  taskAdapter = DEFAULT_TASK_ADAPTER,
  goalPort = DEFAULT_GOAL_PORT,
}: JaApplicationProps): ReactElement {
  const workspaceReferenceTargetRef = useRef<
    ((reference: ComposerWorkspaceReferenceTarget) => void) | undefined
  >(undefined);
  const {
    boot,
    runtimeState,
    turnAdmissionReady,
    lastConfigurationEvent,
    lastThreadMetadataEvent,
  } = useRuntimeState();
  const { startRuntime, generalWorkspace, queryRuntime } = useRuntimeLifecycle();
  const resolvedWorkbenchAdapters = workbenchAdapters ?? DEFAULT_WORKBENCH_ADAPTERS;
  const resolvedHistoryAdapter = historyAdapter ?? DEFAULT_HISTORY_ADAPTER;
  /** Files 与当前 Composer 通过一次性 target port 连接，不把 Thread 草稿复制到壳层。 */
  const registerWorkspaceReferenceTarget = useCallback(
    (target: ((reference: ComposerWorkspaceReferenceTarget) => void) | undefined): void => {
      workspaceReferenceTargetRef.current = target;
    },
    [],
  );
  /** 右栏菜单只投递结构化引用并把焦点交还输入器，文件正文仍保持未读取。 */
  const addWorkspaceReference = useCallback((reference: ComposerWorkspaceReferenceTarget): void => {
    workspaceReferenceTargetRef.current?.(reference);
    focusConversationComposerAfterNavigation();
  }, []);
  const taskTranscriptPort = useMemo<TaskTranscriptPort>(
    () => ({
      /** Child Transcript 复用严格 thread/read adapter，但只投影 Task UI 所需字段。 */
      read: async (input) => {
        const snapshot = await resolvedHistoryAdapter.threadRead(input);
        return {
          threadId: snapshot.threadId,
          revision: snapshot.revision,
          turns: snapshot.turns.map((turn) => ({
            turnId: turn.turnId,
            status: turn.status,
            requestedAt: turn.requestedAt,
            updatedAt: turn.updatedAt,
            completedAt: turn.completedAt,
            errorCode: turn.errorCode,
          })),
          items: snapshot.items.map((item) => ({ ...item })),
          nextCursor: snapshot.nextCursor,
        };
      },
    }),
    [resolvedHistoryAdapter],
  );
  const taskThreadRenamePort = useMemo<TaskThreadRenamePort>(
    () => ({
      /** Task 标题复用同一 Thread rename 命令，不为侧边任务扩展重复协议。 */
      rename: (input) => resolvedHistoryAdapter.threadRename(input),
    }),
    [resolvedHistoryAdapter],
  );
  const [conversationFocusAvailable, setConversationFocusAvailable] = useState(false);
  const pendingSettingsReturnFocusRef = useRef(false);
  const [conversationSearchOpen, setConversationSearchOpen] = useState(false);
  const workspaceReferencePreviewSequenceRef = useRef(0);
  const workspaceReferencePreviewSourceRef = useRef<
    | {
        requestId: number;
        workspaceId: string;
        source: HTMLButtonElement;
      }
    | undefined
  >(undefined);
  const attachmentPreviewSourceRef = useRef<HTMLButtonElement | null>(null);
  const [settingsWorkspaceScope, setSettingsWorkspaceScope] = useState<WorkspaceProjection>();
  const [gitBranchProjection, setGitBranchProjection] = useState<{
    workspaceId: string;
    branch: string;
  }>();
  /**
   * 只接收 Workbench 已读取的 Review Catalog 分支；带 workspace identity 的清理不会让旧项目
   * 卸载回调覆盖新项目投影，也不会在壳层创建第二次 Git 查询。
   */
  const publishGitBranch = useCallback((workspaceId: string, branch: string | undefined): void => {
    setGitBranchProjection((current) => {
      if (branch === undefined) return current?.workspaceId === workspaceId ? undefined : current;
      if (current?.workspaceId === workspaceId && current.branch === branch) return current;
      return { workspaceId, branch };
    });
  }, []);
  const filesCapability = useFilesCapabilityCloseController();
  const workspaceChange = useWorkspaceChangeController(
    filesCapability.current,
    resolvedWorkbenchAdapters.terminal,
  );
  /** 托盘退出失败只发布稳定反馈，具体 Files 冲突仍由 Files feature 展示。 */
  const reportAppExitFailure = useCallback((): void => {
    toast.error("Ja 未能安全退出，请处理未保存或冲突的文件后重试。", {
      id: "ja-app-exit-failed",
    });
  }, []);
  useAppExitController(
    APP_EXIT_OBSERVER,
    filesCapability.current,
    workspaceChange.currentPreview,
    reportAppExitFailure,
  );
  /** Settings 只通过组合层取得外观事实与动作，不直接依赖具体 Zustand 持久化实现。 */
  const themeMode = useUiPreferencesStore((state) => state.themeMode);
  const palette = useUiPreferencesStore((state) => state.palette);
  const reduceMotion = useUiPreferencesStore((state) => state.reduceMotion);
  const reducedTransparency = useUiPreferencesStore((state) => state.reducedTransparency);
  const highContrast = useUiPreferencesStore((state) => state.highContrast);
  const setThemeMode = useUiPreferencesStore((state) => state.setThemeMode);
  const setPalette = useUiPreferencesStore((state) => state.setPalette);
  const setHighContrast = useUiPreferencesStore((state) => state.setHighContrast);
  const setReduceMotion = useUiPreferencesStore((state) => state.setReduceMotion);
  const setReducedTransparency = useUiPreferencesStore((state) => state.setReducedTransparency);
  const appearancePort = useMemo<SettingsAppearancePort>(
    () => ({
      themeMode,
      palette,
      reducedMotion: reduceMotion,
      reducedTransparency,
      highContrast,
      setThemeMode,
      setPalette,
      setHighContrast,
      setReduceMotion,
      setReducedTransparency,
    }),
    [
      highContrast,
      palette,
      reduceMotion,
      reducedTransparency,
      setHighContrast,
      setPalette,
      setReduceMotion,
      setReducedTransparency,
      setThemeMode,
      themeMode,
    ],
  );
  /**
   * composition 只在此处把通用 Runtime query 适配为 Settings 领域能力，
   * 从而让 feature application 不认识 JA-RPC method 或 params envelope。
   */
  const settingsRuntimePort = useMemo<SettingsRuntimePort>(
    () => ({
      listSkills: (input) => queryRuntime("skill/list", input ?? {}),
      listMcpServers: () => queryRuntime("mcp/list", {}),
      testMcp: (mcpRevision) => queryRuntime("mcp/test", { mcpId: mcpRevision }),
      testModel: (providerId, modelId) => queryRuntime("model/test", { providerId, modelId }),
      listMcpTools: (mcpRevision) => queryRuntime("mcp/list-tools", { mcpId: mcpRevision }),
    }),
    [queryRuntime],
  );
  const settings = useSettingsController({
    adapter: settingsAdapter ?? DEFAULT_SETTINGS_ADAPTER,
    appearancePort,
    workspaceScope: settingsWorkspaceScope,
    runtimeState,
    boot,
    configurationChange: projectSettingsConfigurationChange(lastConfigurationEvent),
    runtimePort: settingsRuntimePort,
  });
  /** 从 v4 默认选择派生当前模型；缺失或悬空选择必须让应用进入设置门禁。 */
  const activeModel = useMemo(() => {
    const selection = settings.snapshot.defaultSelection;
    if (selection === null) return undefined;
    const provider = settings.snapshot.providers.find(
      (candidate) => candidate.providerId === selection.providerId,
    );
    const model = provider?.models.find((candidate) => candidate.modelId === selection.modelId);
    return provider === undefined || model === undefined ? undefined : { provider, model };
  }, [settings.snapshot.defaultSelection, settings.snapshot.providers]);
  const workspace = useWorkspaceController({
    history: resolvedHistoryAdapter,
    picker: projectPicker ?? DEFAULT_WORKSPACE_PICKER,
    generalWorkspace,
    runtimeState,
    configurationReady: activeModel !== undefined && settings.scopeReady,
    beforeWorkspaceChange: workspaceChange.beforeChange,
    onWorkspaceCommitted: setSettingsWorkspaceScope,
  });
  const activeWorkspaceId = workspace.workspace?.workspaceId;
  const currentGitBranch =
    gitBranchProjection !== undefined &&
    gitBranchProjection.workspaceId === workspace.workspace?.workspaceId
      ? gitBranchProjection.branch
      : undefined;
  const conversation = useConversationController({
    history: resolvedHistoryAdapter,
    workspace: workspace.workspace,
    workspaceRevision: workspace.revision,
    modelSelection: settings.snapshot.defaultSelection ?? undefined,
    accessMode: settings.snapshot.defaultAccessMode,
    runtimeState,
    metadataEvent: lastThreadMetadataEvent,
    activateWorkspace: workspace.activateForConversation,
  });
  const currentThread = conversation.threads.find(
    (thread) => thread.threadId === conversation.currentThreadId,
  );
  const currentParentThreadRevision = currentThread?.revision;
  // Workspace 和 Thread 目录可能分两次提交，不允许旧 Thread 在新项目短暂挂载工作面。
  const workbenchThreadId =
    currentThread?.workspaceId === workspace.workspace?.workspaceId
      ? conversation.currentThreadId
      : undefined;
  // 原生文件/PTY 属于桌面会话，不因 Java 重连卸载；Review 的运行时代际栅栏独立保留。
  const workbenchScopeIdentity = `${workspace.workspace?.workspaceId ?? "unavailable"}:${workbenchThreadId}`;
  const turnReviewScopeIdentity = `${runtimeState?.serverInstanceId ?? "unavailable"}:${runtimeState?.generation ?? "unavailable"}:${
    workspace.workspace?.workspaceId ?? "unavailable"
  }:${conversation.currentThreadId}`;
  const [attachmentPreviewState, setAttachmentPreviewState] = useThreadWorkbenchState<
    { workspaceId: string; target: AttachmentPreviewTarget } | undefined
  >(turnReviewScopeIdentity, undefined);
  const [workspaceReferencePreviewRequest, setWorkspaceReferencePreviewRequest] =
    useThreadWorkbenchState<WorkspaceReferencePreviewRequest | undefined>(
      turnReviewScopeIdentity,
      undefined,
    );
  const [latestTurnReview, setLatestTurnReview] = useThreadWorkbenchState<
    TurnReviewTarget | undefined
  >(turnReviewScopeIdentity, undefined);
  const [openedTurnReview, setOpenedTurnReview] = useThreadWorkbenchState<
    TurnReviewTarget | undefined
  >(turnReviewScopeIdentity, undefined);
  const [openedTurnReviewLabel, setOpenedTurnReviewLabel] = useThreadWorkbenchState(
    turnReviewScopeIdentity,
    "最后一轮",
  );
  const [requestedTurnReviewPath, setRequestedTurnReviewPath] = useThreadWorkbenchState<
    string | undefined
  >(turnReviewScopeIdentity, undefined);
  const [requestedTurnReviewPathRevision, setRequestedTurnReviewPathRevision] =
    useThreadWorkbenchState(turnReviewScopeIdentity, 0);
  const [turnReviewVisible, setTurnReviewVisible] = useThreadWorkbenchState(
    turnReviewScopeIdentity,
    false,
  );
  const [reviewSelectionScope, setReviewSelectionScope] = useThreadWorkbenchState<
    string | undefined
  >(turnReviewScopeIdentity, undefined);
  const attachmentPreviewTarget =
    attachmentPreviewState?.workspaceId === activeWorkspaceId
      ? attachmentPreviewState?.target
      : undefined;
  const activeWorkbenchScopeRef = useRef(turnReviewScopeIdentity);
  /** commit 即撤销旧来源；隐藏会话迟到完成只改其投影，不得清空新来源或抢焦点。 */
  useLayoutEffect(() => {
    activeWorkbenchScopeRef.current = turnReviewScopeIdentity;
    workspaceReferencePreviewSourceRef.current = undefined;
    attachmentPreviewSourceRef.current = null;
  }, [turnReviewScopeIdentity]);
  const planGoalAvailable =
    runtimeState !== undefined &&
    (runtimeState.status === "ready" || runtimeState.status === "busy") &&
    runtimeState.features.includes("plan_goal_v1");
  /** Task Composer 只接收当前代际启用且健康的 Skill 摘要，激活时仍由 App Server 校验。 */
  const taskComposerSkills = useMemo<TaskComposerSkillSuggestion[]>(
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
  const scopedLatestTurnReview =
    latestTurnReview !== undefined &&
    latestTurnReview.workspaceId === workspace.workspace?.workspaceId &&
    latestTurnReview.threadId === conversation.currentThreadId
      ? latestTurnReview
      : undefined;
  const latestTurnReviewAvailable = canOpenTurnReview(scopedLatestTurnReview);
  const retainedTurnReviewLabel = resolveRetainedTurnLabel(
    openedTurnReview,
    openedTurnReviewLabel,
    scopedLatestTurnReview,
  );
  /**
   * 最近终态只更新“最后一轮”入口；已打开历史目标保持冻结，后续 Turn 不能替换阅读内容。
   */
  const publishLatestTurnReview = useCallback(
    (target: TurnReviewTarget | undefined): void => {
      setLatestTurnReview(target);
    },
    [setLatestTurnReview],
  );
  const retryWorkspaceCatalog = workspace.retryCatalog;

  /**
   * 新目录只有在首个 Thread 落盘后才会进入 Java workspace/list；由 composition 在会话
   * commit 后请求 workspace controller 重读目录，两个 controller 仍不互相拥有状态。
   */
  useEffect(() => {
    if (workspace.workspace?.kind !== "project" || conversation.currentThreadId === undefined)
      return;
    void retryWorkspaceCatalog();
  }, [conversation.currentThreadId, retryWorkspaceCatalog, workspace.workspace]);

  /** 复用 RuntimeProvider 的串行 lifecycle lane，拒绝在视图层直接调用 raw Tauri start。 */
  const retryRuntime = useCallback((): void => {
    void startRuntime().catch(() => undefined);
  }, [startRuntime]);
  const required = settings.scopeReady && activeModel === undefined;
  const pageNavigation = usePageNavigationController(required);
  const { settingsVisible, settingsSection, setSettingsSection, navigate, goBack, goForward } =
    pageNavigation;
  const isProjectScope = workspace.workspace?.kind === "project";
  // native general scope 与已选项目都是有效 conversation scope。Navigation action 与
  // Composer 等待相同 lifecycle/history gate，启动期不能在 Thread/runtime admission
  // 成为权威前暴露可点击动作。
  const settingsScopeReady =
    settings.scopeReady &&
    settings.scopeWorkspaceId ===
      (workspace.workspace?.kind === "project" ? workspace.workspace.workspaceId : undefined);
  const conversationScopeReady =
    workspace.workspace !== undefined &&
    settingsScopeReady &&
    turnAdmissionReady &&
    (boot.status === "ready" || boot.status === "busy") &&
    !workspace.busy &&
    !conversation.busy;
  const responsiveShell = useResponsiveShellController(
    required,
    settingsVisible,
    workspace.workspace !== undefined,
    workbenchThreadId === undefined ? undefined : workbenchScopeIdentity,
  );
  const {
    compactNavigation,
    singlePaneWorkbench,
    sidebarVisible,
    sidebarPreviewRatio,
    inspectorOpen,
    workbenchSize,
    workbenchVisible,
    workbenchTab,
    workbenchTabs,
    setSidebarPreviewRatio,
    commitSidebarRatio,
    setWorkbenchPreviewSize,
    commitWorkbenchSize,
    setInspectorOpen,
    setWorkbenchTab,
    setWorkbenchTabs,
    toggleInspector,
    toggleSidebar,
    closeSidebar,
  } = responsiveShell;
  const goal = useGoalController({
    goalId: planGoalAvailable ? (currentThread?.activeGoalId ?? undefined) : undefined,
    ownerThreadId: conversation.currentThreadId,
    visible: planGoalAvailable,
    detailsVisible:
      planGoalAvailable && inspectorOpen && workbenchVisible && workbenchTab === "plan",
    port: goalPort,
  });
  const composerNativeDrop = useComposerNativeDropRouter(
    nativeDropPort,
    !settingsVisible && conversationScopeReady,
  );

  /**
   * Workspace 引用只签发当前 Workspace 的一次性 Files 打开请求；来源按钮单独保留给关闭与
   * 失败恢复，引用本身仍保持结构化上下文，不会被转换为上传附件。
   */
  const openWorkspaceReferencePreview = useCallback(
    (reference: ComposerWorkspaceReferenceTarget, source: HTMLButtonElement): void => {
      const workspaceId = workspace.workspace?.workspaceId;
      if (workspaceId === undefined || reference.workspaceId !== workspaceId) {
        toast.error("该文件引用不属于当前工作区，请重新添加。", {
          id: "ja-files:workspace-reference-scope",
        });
        restoreConversationObjectFocus(source);
        return;
      }
      const requestId = ++workspaceReferencePreviewSequenceRef.current;
      workspaceReferencePreviewSourceRef.current = { requestId, workspaceId, source };
      setWorkspaceReferencePreviewRequest({ requestId, reference });
      if (!workbenchTabs.includes("files")) setWorkbenchTabs([...workbenchTabs, "files"]);
      setWorkbenchTab("files");
      setInspectorOpen(true);
    },
    [
      setInspectorOpen,
      setWorkbenchTab,
      setWorkbenchTabs,
      setWorkspaceReferencePreviewRequest,
      workbenchTabs,
      workspace.workspace?.workspaceId,
    ],
  );

  /** 只结算当前 request；失败关闭 Files 并归还来源焦点，成功则保留来源供显式关闭时返回。 */
  const settleWorkspaceReferencePreview = useCallback(
    (requestId: number, outcome: WorkspaceReferencePreviewOutcome): void => {
      if (workspaceReferencePreviewRequest?.requestId !== requestId) return;
      if (outcome === "opened") return;
      setWorkspaceReferencePreviewRequest(undefined);
      setInspectorOpen(false);
      if (activeWorkbenchScopeRef.current !== turnReviewScopeIdentity) return;
      const source = workspaceReferencePreviewSourceRef.current;
      workspaceReferencePreviewSourceRef.current = undefined;
      restoreConversationObjectFocus(
        source?.requestId === requestId && source.workspaceId === workspace.workspace?.workspaceId
          ? source.source
          : null,
      );
    },
    [
      setInspectorOpen,
      setWorkspaceReferencePreviewRequest,
      turnReviewScopeIdentity,
      workspace.workspace?.workspaceId,
      workspaceReferencePreviewRequest?.requestId,
    ],
  );

  /**
   * 对象级附件点击可以显式打开右栏并选择 Preview；来源按钮与 target 一起冻结，关闭后才
   * 恢复焦点，避免窄窗切换或异步 session 打开把键盘用户留在隐藏面板。
   */
  const openAttachmentPreview = useCallback(
    (target: AttachmentPreviewTarget, source: HTMLButtonElement): void => {
      const workspaceId = workspace.workspace?.workspaceId;
      if (workspaceId === undefined) return;
      attachmentPreviewSourceRef.current = source;
      setAttachmentPreviewState({ workspaceId, target });
      if (!workbenchTabs.includes("preview")) setWorkbenchTabs([...workbenchTabs, "preview"]);
      setWorkbenchTab("preview");
      setInspectorOpen(true);
    },
    [
      setAttachmentPreviewState,
      setInspectorOpen,
      setWorkbenchTab,
      setWorkbenchTabs,
      workbenchTabs,
      workspace.workspace?.workspaceId,
    ],
  );

  /**
   * 终态入口携带自己的 frozen target；后续 Turn 只更新“最后一轮”菜单，不改写已打开对象。
   */
  const openTurnReview = useCallback(
    (target: TurnReviewTarget, scopeLabel = "最后一轮", requestedPath?: string): boolean => {
      if (
        target.workspaceId !== workspace.workspace?.workspaceId ||
        target.threadId !== conversation.currentThreadId ||
        target.stats.files === 0
      )
        return false;
      setOpenedTurnReview(target);
      setOpenedTurnReviewLabel(scopeLabel);
      setRequestedTurnReviewPath(requestedPath);
      if (requestedPath !== undefined) setRequestedTurnReviewPathRevision((current) => current + 1);
      setTurnReviewVisible(true);
      setReviewSelectionScope(turnReviewScopeIdentity);
      if (!workbenchTabs.includes("review")) setWorkbenchTabs([...workbenchTabs, "review"]);
      setWorkbenchTab("review");
      setInspectorOpen(true);
      return true;
    },
    [
      conversation.currentThreadId,
      setInspectorOpen,
      setOpenedTurnReview,
      setOpenedTurnReviewLabel,
      setRequestedTurnReviewPath,
      setRequestedTurnReviewPathRevision,
      setReviewSelectionScope,
      setTurnReviewVisible,
      setWorkbenchTab,
      setWorkbenchTabs,
      turnReviewScopeIdentity,
      workbenchTabs,
      workspace.workspace?.workspaceId,
    ],
  );

  /** 命令面板只消费当前摘要；具体 Timeline 卡必须改走携带 frozen target 的打开函数。 */
  const openLatestTurnReview = useCallback(
    (): boolean =>
      scopedLatestTurnReview === undefined
        ? false
        : openTurnReview(scopedLatestTurnReview, "最后一轮"),
    [openTurnReview, scopedLatestTurnReview],
  );

  /**
   * Review Tab 首次进入当前 Workspace/Thread 时默认 Git 未提交；一旦用户
   * 选择 Git 或某个历史 Turn，同作用域后续消息只更新事实，不再抢占正在阅读的来源。
   */
  const selectInitialReviewSource = useCallback((): void => {
    const decision = decideInitialReviewSource(reviewSelectionScope, turnReviewScopeIdentity);
    if (decision.kind === "preserve") return;
    setReviewSelectionScope(turnReviewScopeIdentity);
    setTurnReviewVisible(false);
  }, [
    reviewSelectionScope,
    setReviewSelectionScope,
    setTurnReviewVisible,
    turnReviewScopeIdentity,
  ]);

  /** 保留最近一次 Turn 描述并重新显示；切到 Git 只释放正文 session，不销毁返回目标。 */
  const showRetainedTurnReview = useCallback((): void => {
    if (openedTurnReview === undefined) {
      openLatestTurnReview();
      return;
    }
    setReviewSelectionScope(turnReviewScopeIdentity);
    setTurnReviewVisible(true);
  }, [
    openLatestTurnReview,
    openedTurnReview,
    setReviewSelectionScope,
    setTurnReviewVisible,
    turnReviewScopeIdentity,
  ]);

  /** 主 Timeline 活动卡只打开稳定 Task 实例；显示动作不得隐式唤醒或取消 Child。 */
  const openTaskFromTimeline = useCallback(
    (task: TaskSummary): void => {
      const tab = taskWorkbenchTab({ ...task, label: task.taskName });
      if (!workbenchTabs.includes(tab.key)) setWorkbenchTabs([...workbenchTabs, tab.key]);
      setWorkbenchTab(tab.key);
      setInspectorOpen(true);
    },
    [setInspectorOpen, setWorkbenchTab, setWorkbenchTabs, workbenchTabs],
  );

  /** 切到 Git 时释放 Turn 当前正文，但保留 target 与标签，允许无歧义返回原审阅轮次。 */
  const dismissTurnReview = useCallback((): void => {
    setReviewSelectionScope(turnReviewScopeIdentity);
    setTurnReviewVisible(false);
  }, [setReviewSelectionScope, setTurnReviewVisible, turnReviewScopeIdentity]);

  /** 返回网页预览后优先恢复来源；来源已因移除或 Thread 切换消失时回退到当前 Composer。 */
  const dismissAttachmentPreview = useCallback(
    (target: AttachmentPreviewTarget): void => {
      const source = attachmentPreviewSourceRef.current;
      setAttachmentPreviewState((current) =>
        current?.target.attachmentId === target.attachmentId ? undefined : current,
      );
      if (activeWorkbenchScopeRef.current !== turnReviewScopeIdentity) return;
      attachmentPreviewSourceRef.current = null;
      const restoreFocus = (): void => {
        if (activeWorkbenchScopeRef.current !== turnReviewScopeIdentity) return;
        if (source?.isConnected) {
          source.focus();
          return;
        }
        const composer = document.querySelector<HTMLTextAreaElement>('[aria-label="消息"]');
        if (composer?.isConnected && !composer.disabled) composer.focus();
      };
      if (typeof window.requestAnimationFrame === "function")
        window.requestAnimationFrame(restoreFocus);
      else restoreFocus();
    },
    [setAttachmentPreviewState, turnReviewScopeIdentity],
  );

  /** 成功移除的附件不能继续保留授权 session；失败移除仍维持当前预览和焦点。 */
  const closeRemovedAttachmentPreview = useCallback(
    (attachmentId: string): void => {
      const current = attachmentPreviewTarget;
      if (current?.attachmentId === attachmentId) dismissAttachmentPreview(current);
    },
    [attachmentPreviewTarget, dismissAttachmentPreview],
  );

  /** Turn ACK 后把同一预览从 Workspace 草稿授权原子切换为 Thread 授权，不改变所见对象。 */
  const bindAttachmentPreview = useCallback(
    (threadId: string, attachmentIds: readonly string[]): void => {
      setAttachmentPreviewState((current) =>
        current !== undefined && attachmentIds.includes(current.target.attachmentId)
          ? {
              ...current,
              target: { ...current.target, authorization: { kind: "thread", threadId } },
            }
          : current,
      );
    },
    [setAttachmentPreviewState],
  );
  const projectSectionCollapsed = useUiPreferencesStore((state) => state.projectSectionCollapsed);
  const setProjectSectionCollapsed = useUiPreferencesStore(
    (state) => state.setProjectSectionCollapsed,
  );
  const historySectionCollapsed = useUiPreferencesStore((state) => state.historySectionCollapsed);
  const setHistorySectionCollapsed = useUiPreferencesStore(
    (state) => state.setHistorySectionCollapsed,
  );
  const desktopNotificationsEnabled = useUiPreferencesStore((state) => state.desktopNotifications);
  const setDesktopNotifications = useUiPreferencesStore((state) => state.setDesktopNotifications);
  const [commandPaletteOpen, setCommandPaletteOpen] = useState(false);
  const platform = useMemo(() => detectDesktopPlatform(), []);
  /** 窗口动作错误统一在壳层显示，adapter 不泄漏系统错误或私有路径。 */
  const reportWindowActionFailure = useCallback((): void => {
    toast.error("窗口操作失败，请重试。", { id: "ja-window-action-failed" });
  }, []);
  const windowFrame = useWindowFrameController(
    navigationAdapters.windowFrame,
    platform === "windows" || platform === "macos",
    reportWindowActionFailure,
  );
  const workspaceShortcutCapabilitiesEnabled =
    workspace.workspace !== undefined && !settingsVisible;
  const conversationShortcutFocusEnabled = !settingsVisible && conversationFocusAvailable;

  /** 设置返回可能触发配置准入短暂同步；只在 Composer 真正可用后消费一次显式焦点意图。 */
  useEffect(() => {
    if (!pendingSettingsReturnFocusRef.current || !conversationShortcutFocusEnabled) return;
    pendingSettingsReturnFocusRef.current = false;
    focusConversationComposerAfterNavigation();
  }, [conversationShortcutFocusEnabled]);
  const conversationSummaryContext = useMemo<
    Pick<ConversationSummary, "scope" | "gitBranch" | "model" | "runtime">
  >(
    () => ({
      scope: isProjectScope ? (workspace.workspace?.displayName ?? "当前项目") : "无项目对话",
      gitBranch: isProjectScope ? currentGitBranch : undefined,
      model:
        activeModel === undefined
          ? undefined
          : `${activeModel.provider.name} · ${activeModel.model.name}`,
      runtime: runtimeLabel(boot.status),
    }),
    [boot.status, currentGitBranch, isProjectScope, activeModel, workspace.workspace?.displayName],
  );

  /**
   * 会话聚焦快捷键不改变抽屉开闭；抽屉已打开时返回 false，让唯一的抽屉按钮继续拥有
   * 布局状态，避免 Review/Files/Terminal/Preview 快捷键与面板按钮互相争用。
   */
  const returnToConversation = useCallback((): boolean => {
    if (!conversationShortcutFocusEnabled || inspectorOpen) return false;
    const composer = document.querySelector<HTMLTextAreaElement>('[aria-label="消息"]');
    if (composer === null || composer.disabled) return false;
    /** 延后一帧避开当前快捷键事件的焦点回写，并核对同一 Composer 仍在文档中。 */
    const focusComposer = (): void => {
      if (composer.isConnected && !composer.disabled) composer.focus();
    };
    if (typeof window.requestAnimationFrame === "function")
      window.requestAnimationFrame(focusComposer);
    else focusComposer();
    return true;
  }, [conversationShortcutFocusEnabled, inspectorOpen]);

  /** `/chat` 明确拥有关闭 Workbench 的意图；延迟聚焦避免 React 布局提交覆盖 textarea 焦点。 */
  const closeWorkbenchAndReturnToConversation = useCallback((): boolean => {
    if (!conversationShortcutFocusEnabled || !inspectorOpen) return false;
    const composer = document.querySelector<HTMLTextAreaElement>('[aria-label="消息"]');
    if (composer === null || composer.disabled) return false;
    setInspectorOpen(false);
    const focusComposer = (): void => {
      if (composer.isConnected && !composer.disabled) composer.focus();
    };
    if (typeof window.requestAnimationFrame === "function")
      window.requestAnimationFrame(focusComposer);
    else focusComposer();
    return true;
  }, [conversationShortcutFocusEnabled, inspectorOpen, setInspectorOpen]);

  /** 会话 Host 已完成自己的 flush；这里只归还焦点，不再重复冻结同项目其它会话。 */
  const closeFilesCapability = useCallback(
    async (workspaceId: string): Promise<void> => {
      if (activeWorkbenchScopeRef.current !== turnReviewScopeIdentity) return;
      const source = workspaceReferencePreviewSourceRef.current;
      if (
        source === undefined ||
        source.workspaceId !== workspaceId ||
        workspaceReferencePreviewSourceRef.current?.requestId !== source.requestId
      )
        return;
      setWorkspaceReferencePreviewRequest((current) =>
        current?.requestId === source.requestId ? undefined : current,
      );
      workspaceReferencePreviewSourceRef.current = undefined;
      restoreConversationObjectFocus(source.source);
    },
    [setWorkspaceReferencePreviewRequest, turnReviewScopeIdentity],
  );

  /** Workbench 抽屉关闭时，只有由引用进入的 Files 视图恢复对象焦点，其余能力保持原行为。 */
  const closeWorkbench = useCallback((): void => {
    setInspectorOpen(false);
    if (workbenchTab !== "files") return;
    const source = workspaceReferencePreviewSourceRef.current;
    setWorkspaceReferencePreviewRequest(undefined);
    workspaceReferencePreviewSourceRef.current = undefined;
    restoreConversationObjectFocus(
      source !== undefined && source.workspaceId === workspace.workspace?.workspaceId
        ? source.source
        : null,
    );
  }, [
    setInspectorOpen,
    setWorkspaceReferencePreviewRequest,
    workbenchTab,
    workspace.workspace?.workspaceId,
  ]);

  useDesktopNotifications({
    enabled: desktopNotificationsEnabled,
    notify: desktopAdapters.notify,
    isWindowFocused: desktopAdapters.isWindowFocused,
  });

  /** native permission 成功后才持久化 opt-in；关闭开关立即生效，且不调用或扩大 OS capability。 */
  const changeDesktopNotifications = useCallback(
    async (enabled: boolean): Promise<boolean> => {
      if (!enabled) {
        setDesktopNotifications(false);
        return false;
      }
      const granted = await desktopAdapters.enableNotifications();
      setDesktopNotifications(granted);
      return granted;
    },
    [desktopAdapters, setDesktopNotifications],
  );

  const desktopNotificationPreference = useMemo<DesktopNotificationPreference>(
    () => ({
      enabled: desktopNotificationsEnabled,
      onChange: changeDesktopNotifications,
    }),
    [changeDesktopNotifications, desktopNotificationsEnabled],
  );

  /**
   * 能力快捷键只切换已打开抽屉中的 Tab；关闭状态返回 false，禁止快捷键绕过抽屉按钮
   * 打开右栏，也避免隐藏区域接收焦点或创建重型能力会话。
   */
  const openWorkbenchCapability = useCallback(
    (tab: WorkbenchTabKey): boolean => {
      if (!workspaceShortcutCapabilitiesEnabled || !inspectorOpen) return false;
      if (tab === "review") selectInitialReviewSource();
      setWorkbenchTab(tab);
      return true;
    },
    [
      inspectorOpen,
      selectInitialReviewSource,
      setWorkbenchTab,
      workspaceShortcutCapabilitiesEnabled,
    ],
  );

  /** Composer 指令显式打开已选能力；只有用户执行后才挂载 Workbench，查询面板本身零重型 IO。 */
  const showWorkbenchCapability = useCallback(
    (tab: WorkbenchTabKey): void => {
      if (workspace.workspace === undefined) throw new Error("workspace unavailable");
      if (tab === "review") selectInitialReviewSource();
      if (!workbenchTabs.includes(tab)) setWorkbenchTabs([...workbenchTabs, tab]);
      setWorkbenchTab(tab);
      setInspectorOpen(true);
    },
    [
      selectInitialReviewSource,
      setInspectorOpen,
      setWorkbenchTab,
      setWorkbenchTabs,
      workbenchTabs,
      workspace.workspace,
    ],
  );

  /** Workbench 内切换 Tab 与快捷键/命令共享首次 Review 默认策略。 */
  const changeWorkbenchTab = useCallback(
    (tab: WorkbenchTabKey): void => {
      if (tab === "review") selectInitialReviewSource();
      setWorkbenchTab(tab);
    },
    [selectInitialReviewSource, setWorkbenchTab],
  );

  const workbenchCapabilityShortcuts = useMemo<Partial<Record<WorkbenchCapability, string>>>(
    () => ({
      review: navigationShortcut("open-review", platform).display,
      files: navigationShortcut("open-files", platform).display,
      terminal: navigationShortcut("open-terminal", platform).display,
      preview: navigationShortcut("open-preview", platform).display,
    }),
    [platform],
  );

  /**
   * 从 Settings、侧栏或命令入口完成 Thread 创建/复用后再聚焦 Composer；延后一帧让页面导航、
   * 紧凑侧栏关闭和新 Thread 渲染先提交，避免按钮或 Dialog 的焦点归还覆盖输入起点。
   */
  const createConversation = useCallback(async (): Promise<void> => {
    await conversation.create();
    navigate("workspace");
    focusConversationComposerAfterNavigation();
  }, [conversation, navigate]);

  /** Picker 取消由 Session 拥有；只有项目真实打开后才切换页面。 */
  const chooseProject = useCallback(async (): Promise<void> => {
    await workspace.choose();
  }, [workspace]);

  /** 持久项目与新选择目录共用同一 native configure/history 路径。 */
  const selectProject = useCallback(
    async (workspaceId: string): Promise<void> => {
      await workspace.select(workspaceId);
    },
    [workspace],
  );

  /** 显式返回受管 general workspace，补全项目范围的退出路径而不清除任何历史项目。 */
  const selectGeneral = useCallback(async (): Promise<void> => {
    await workspace.selectGeneral();
  }, [workspace]);

  /** 恢复持久 Thread 后再转交输入焦点，避免侧栏或搜索结果继续持有键盘落点。 */
  const selectConversation = useCallback(
    async (threadId: string): Promise<void> => {
      await conversation.select(threadId);
      navigate("workspace");
      focusConversationComposerAfterNavigation();
    },
    [conversation, navigate],
  );

  /** 行级置顶失败只显示固定产品文案，不把原生异常或路径带入 Toast。 */
  const pinConversation = useCallback(
    async (threadId: string, pinned: boolean): Promise<void> => {
      try {
        await conversation.pin(threadId, pinned);
      } catch {
        toast.error(pinned ? "置顶失败，请重试。" : "取消置顶失败，请重试。");
      }
    },
    [conversation],
  );

  /** 归档成功后提供 8 秒撤销；恢复始终调用服务端 restore，不复用本地旧行。 */
  const archiveConversation = useCallback(
    async (threadId: string): Promise<void> => {
      try {
        const undo = await conversation.archive(threadId);
        if (undo === undefined) return;
        toast.success("对话已归档", {
          duration: 8_000,
          action: {
            label: "撤销",
            onClick: () => {
              void conversation
                .restore(undo.archived.threadId, undo.restoreSelection)
                .catch(() => toast.error("恢复失败，请从搜索中重试。"));
            },
          },
        });
      } catch (failure) {
        const code =
          failure !== null && typeof failure === "object"
            ? (failure as Record<string, unknown>)["code"]
            : undefined;
        toast.error(
          code === "THREAD_BUSY" || code === "INVALID_STATE"
            ? "回复结束后可归档。"
            : "归档失败，请重试。",
        );
      }
    },
    [conversation],
  );

  /** 搜索中的归档结果先恢复再打开，并与普通会话切换共享 Composer 焦点终点。 */
  const restoreConversation = useCallback(
    async (threadId: string): Promise<void> => {
      try {
        await conversation.restore(threadId, true);
        navigate("workspace");
        focusConversationComposerAfterNavigation();
      } catch {
        toast.error("恢复失败，请重试。");
      }
    },
    [conversation, navigate],
  );
  /** 对话搜索开关保持稳定 identity，避免 Timeline 更新穿透 memoized NavigationSidebar。 */
  const openConversationSearch = useCallback((): void => {
    setConversationSearchOpen(true);
  }, []);
  /** 设置导航保持稳定 identity；实际历史栈仍由 page navigation controller 唯一持有。 */
  const openSettings = useCallback((): void => {
    navigate("settings");
  }, [navigate]);
  /** 设置层关闭后复用统一会话焦点终点，避免键盘用户返回到已卸载的设置按钮。 */
  const returnFromSettings = useCallback((): void => {
    pendingSettingsReturnFocusRef.current = true;
    navigate("workspace");
  }, [navigate]);

  /**
   * DOM 与 native 快捷键共用的唯一动作入口。返回 true 只表示当前状态确实接纳
   * 了动作；不可用命令保持 false，调用方不得据此吞键或伪装成功。
   */
  const dispatchNavigationCommand = useCallback(
    (command: NavigationCommand): boolean => {
      switch (command) {
        case "command-palette":
          setCommandPaletteOpen(true);
          return true;
        case "search-conversations":
          if (required || settingsVisible) return false;
          setConversationSearchOpen(true);
          return true;
        case "toggle-sidebar":
          if (required) return false;
          toggleSidebar();
          return true;
        case "new-conversation":
          if (required || !conversationScopeReady) return false;
          void Promise.resolve(createConversation()).catch(() => undefined);
          return true;
        case "open-review":
          return openWorkbenchCapability("review");
        case "open-files":
          return openWorkbenchCapability("files");
        case "open-terminal":
          return openWorkbenchCapability("terminal");
        case "open-preview":
          return openWorkbenchCapability("preview");
        case "focus-conversation":
          return returnToConversation();
        case "open-settings":
          if (settingsVisible) return false;
          navigate("settings");
          return true;
        case "go-back":
          if (!pageNavigation.canGoBack) return false;
          goBack();
          return true;
        case "go-forward":
          if (!pageNavigation.canGoForward) return false;
          goForward();
          return true;
      }
    },
    [
      conversationScopeReady,
      createConversation,
      goBack,
      goForward,
      navigate,
      openWorkbenchCapability,
      pageNavigation.canGoBack,
      pageNavigation.canGoForward,
      required,
      returnToConversation,
      settingsVisible,
      toggleSidebar,
    ],
  );
  /** 只按 canonical command contract 注册真实壳层 action；条件组合让不可用能力不进入 UI。 */
  const commandActions = useMemo<CommandAction[]>(
    (): CommandAction[] => [
      ...(required || !conversationScopeReady
        ? []
        : [
            {
              id: "new-conversation",
              label: "新建对话",
              keywords: ["新任务", "conversation", workspace.workspace?.displayName ?? "无项目"],
              description: isProjectScope
                ? `在 ${workspace.workspace?.displayName ?? "当前项目"} 中开始新任务`
                : "开始一段新的无项目对话",
              shortcut: navigationShortcut("new-conversation", platform).display,
              availability: true,
              icon: "plus" as const,
              invoke: createConversation,
            },
          ]),
      ...(required
        ? []
        : [
            {
              id: "choose-project",
              label: isProjectScope ? "切换项目" : "关联项目",
              keywords: ["打开目录", "workspace", "folder"],
              description: "选择一个受信任的本地工作区",
              availability: true,
              icon: "folder-open" as const,
              invoke: chooseProject,
            },
          ]),
      ...(settingsVisible
        ? required
          ? []
          : [
              {
                id: "open-workspace",
                label: "返回对话",
                keywords: ["对话", "workspace"],
                description: isProjectScope
                  ? (workspace.workspace?.displayName ?? "当前项目")
                  : "无项目对话",
                availability: true,
                icon: "command" as const,
                invoke: () => navigate("workspace"),
              },
            ]
        : [
            {
              id: "open-settings",
              label: "打开设置",
              keywords: ["偏好", "模型", "权限", "外观"],
              description: "模型、工具、权限与外观",
              shortcut: navigationShortcut("open-settings", platform).display,
              availability: true,
              icon: "settings" as const,
              invoke: () => navigate("settings"),
            },
          ]),
    ],
    [
      chooseProject,
      conversationScopeReady,
      createConversation,
      isProjectScope,
      navigate,
      platform,
      required,
      settingsVisible,
      workspace.workspace,
    ],
  );

  const commandPalette = useCommandPaletteController({
    commands: commandActions,
    onOpenChange: setCommandPaletteOpen,
  });

  /** 十二条首发 slash command 只绑定现有 Ja action，并携带实时不可用原因。 */
  const composerSlashCommands = useMemo<ComposerSlashCommand[]>(
    () => [
      {
        id: "new",
        name: "new",
        aliases: ["new-chat", "新对话"],
        label: "新建对话",
        description: "在当前范围开始新对话",
        shortcut: navigationShortcut("new-conversation", platform).display,
        available: !required && conversationScopeReady && !conversation.busy,
        unavailableReason: required
          ? "完成必要设置后可新建对话"
          : !conversationScopeReady
            ? "当前会话范围尚未就绪"
            : "会话操作正在进行",
        execute: createConversation,
      },
      {
        id: "project",
        name: "project",
        aliases: ["workspace", "项目"],
        label: isProjectScope ? "切换项目" : "关联项目",
        description: "选择受信任的本地工作空间",
        available: !required && !workspace.busy,
        unavailableReason: required ? "完成必要设置后可选择项目" : "项目切换正在进行",
        execute: chooseProject,
      },
      {
        id: "search",
        name: "search",
        aliases: ["threads", "find", "搜索"],
        label: "搜索对话",
        description: "搜索当前工作空间的会话",
        shortcut: navigationShortcut("search-conversations", platform).display,
        available: !required && !conversation.busy,
        unavailableReason: required ? "完成必要设置后可搜索对话" : "会话目录仍在同步",
        execute: () => setConversationSearchOpen(true),
      },
      {
        id: "files",
        name: "files",
        aliases: ["file", "文件"],
        label: "打开文件",
        description: "在 Workbench 中查看当前工作空间",
        shortcut: navigationShortcut("open-files", platform).display,
        available: workspace.workspace !== undefined,
        unavailableReason: "当前没有可用工作空间",
        execute: () => showWorkbenchCapability("files"),
      },
      {
        id: "review",
        name: "review",
        aliases: ["changes", "diff", "审查"],
        label: "审查本轮修改",
        description: "打开本轮可靠修改",
        shortcut: navigationShortcut("open-review", platform).display,
        available: latestTurnReviewAvailable,
        unavailableReason: "本轮没有可审查的修改",
        execute: () => {
          openLatestTurnReview();
        },
      },
      {
        id: "terminal",
        name: "terminal",
        aliases: ["term", "shell", "终端"],
        label: "打开终端",
        description: "在 Workbench 中打开项目终端",
        shortcut: navigationShortcut("open-terminal", platform).display,
        available: workspace.workspace !== undefined,
        unavailableReason: "当前环境不支持终端",
        execute: () => showWorkbenchCapability("terminal"),
      },
      {
        id: "preview",
        name: "preview",
        aliases: ["browser", "预览"],
        label: "打开预览",
        description: "返回当前已有的预览目标",
        shortcut: navigationShortcut("open-preview", platform).display,
        available: attachmentPreviewTarget !== undefined,
        unavailableReason: "当前没有可预览目标",
        execute: () => showWorkbenchCapability("preview"),
      },
      {
        id: "settings",
        name: "settings",
        aliases: ["preferences", "设置"],
        label: "打开设置",
        description: "模型、工具、权限与外观",
        shortcut: navigationShortcut("open-settings", platform).display,
        available: !settingsVisible,
        unavailableReason: "当前已在设置",
        execute: () => navigate("settings"),
      },
      {
        id: "sidebar",
        name: "sidebar",
        aliases: ["toggle-sidebar", "侧栏"],
        label: "切换侧栏",
        description: "显示或隐藏导航侧栏",
        available: !required,
        unavailableReason: "完成必要设置后可切换",
        execute: toggleSidebar,
      },
      {
        id: "back",
        name: "back",
        aliases: ["previous", "返回"],
        label: "后退",
        description: "返回上一页面",
        available: pageNavigation.canGoBack,
        unavailableReason: "没有更早的页面",
        execute: goBack,
      },
      {
        id: "forward",
        name: "forward",
        aliases: ["next", "前进"],
        label: "前进",
        description: "前往下一页面",
        available: pageNavigation.canGoForward,
        unavailableReason: "没有可前进的页面",
        execute: goForward,
      },
      {
        id: "chat",
        name: "chat",
        aliases: ["conversation", "对话"],
        label: "返回对话",
        description: "关闭 Workbench 并聚焦当前对话",
        available: inspectorOpen && conversation.currentThreadId !== undefined,
        unavailableReason:
          conversation.currentThreadId === undefined ? "当前没有活动对话" : "当前已在对话",
        execute: () => {
          if (!closeWorkbenchAndReturnToConversation()) throw new Error("conversation unavailable");
        },
      },
    ],
    [
      attachmentPreviewTarget,
      chooseProject,
      closeWorkbenchAndReturnToConversation,
      conversation.busy,
      conversation.currentThreadId,
      conversationScopeReady,
      createConversation,
      goBack,
      goForward,
      inspectorOpen,
      isProjectScope,
      latestTurnReviewAvailable,
      navigate,
      openLatestTurnReview,
      pageNavigation.canGoBack,
      pageNavigation.canGoForward,
      platform,
      required,
      settingsVisible,
      showWorkbenchCapability,
      toggleSidebar,
      workspace.busy,
      workspace.workspace,
    ],
  );

  const nativeShortcutContext = useMemo(
    () => ({
      projectCapabilitiesEnabled: workspaceShortcutCapabilitiesEnabled,
      conversationFocusEnabled: conversationShortcutFocusEnabled && !inspectorOpen,
    }),
    [conversationShortcutFocusEnabled, inspectorOpen, workspaceShortcutCapabilitiesEnabled],
  );
  useNativeNavigationShortcuts(
    navigationAdapters.nativeShortcuts,
    platform,
    dispatchNavigationCommand,
    nativeShortcutContext,
  );

  const runtimeIssue =
    boot.status === "failed" || boot.status === "degraded" ? (
      <section>
        <p>{boot.message}</p>
        <Button type="button" variant="secondary" onClick={retryRuntime}>
          重新启动
        </Button>
      </section>
    ) : boot.status === "recovery_required" ? (
      <RecoveryPanel />
    ) : boot.status === "stopped" ? (
      <section>
        <p>本地运行时已停止。</p>
        <Button type="button" variant="secondary" onClick={retryRuntime}>
          重新启动
        </Button>
      </section>
    ) : undefined;
  const runtimeIssueReason =
    boot.status === "failed" || boot.status === "degraded"
      ? boot.message
      : boot.status === "recovery_required"
        ? "上一次关闭尚未确认 Ja App Server 已清理，需要人工恢复。"
        : boot.status === "stopped"
          ? "本地运行时已停止，点击可重新启动。"
          : undefined;
  const settingsMainView = settings.loading ? (
    <section className="ja-loading-state" role="status">
      正在读取本地设置…
    </section>
  ) : settings.error !== undefined ? (
    <section className="ja-error-state" role="alert">
      <h1>设置暂时不可用</h1>
      <p>{settings.error}</p>
      <Button type="button" variant="secondary" onClick={() => void settings.reload()}>
        重新读取
      </Button>
    </section>
  ) : (
    <SettingsView
      settings={settings}
      required={required}
      section={settingsSection}
      onSectionChange={setSettingsSection}
      onReturnToApp={returnFromSettings}
      desktopNotifications={desktopNotificationPreference}
      desktop={desktopAdapters}
    />
  );
  const workspaceMainView = (
    <ConversationWorkspace
      workspace={workspace}
      conversation={conversation}
      settings={settings}
      inspectorOpen={inspectorOpen}
      onToggleInspector={toggleInspector}
      summaryContext={conversationSummaryContext}
      workspaceAdapter={resolvedWorkbenchAdapters.workspace}
      onOpenLink={desktopAdapters.openExternalUrl}
      onCopyText={desktopAdapters.writeText}
      onConversationFocusAvailabilityChange={setConversationFocusAvailable}
      onRegisterWorkspaceReferenceTarget={registerWorkspaceReferenceTarget}
      onOpenWorkspaceReference={openWorkspaceReferencePreview}
      attachmentPort={attachmentPort}
      attachmentPreviewPort={attachmentPreviewPort}
      artifactPort={conversationArtifactPort}
      nativeDropEvent={composerNativeDrop.event}
      onRegisterComposerDropZone={composerNativeDrop.registerDropZone}
      onOpenAttachmentPreview={openAttachmentPreview}
      onLatestTurnReviewChange={publishLatestTurnReview}
      onOpenTurnReview={openTurnReview}
      onAttachmentRemoved={closeRemovedAttachmentPreview}
      onAttachmentsBound={bindAttachmentPreview}
      slashCommands={composerSlashCommands}
      onOpenTask={openTaskFromTimeline}
      planGoalAvailable={planGoalAvailable}
      goal={goal}
      onOpenGoal={() => showWorkbenchCapability("plan")}
    />
  );
  const inspectorContent =
    workspace.workspace === undefined ? null : (
      <ThreadWorkbenchSessions
        key={workspace.workspace.workspaceId}
        scopeKey={workbenchScopeIdentity}
        workspace={workspace.workspace}
        generation={runtimeState?.generation}
        adapters={resolvedWorkbenchAdapters}
        active={workbenchVisible}
        rootThreadId={workbenchThreadId}
        parentThreadRevision={currentParentThreadRevision}
        taskPort={taskAdapter}
        taskTranscriptPort={taskTranscriptPort}
        taskThreadRenamePort={taskThreadRenamePort}
        taskAttachmentPort={attachmentPort}
        taskArtifactPort={conversationArtifactPort}
        taskComposerSkills={taskComposerSkills}
        selectedTab={workbenchTab}
        onTabChange={changeWorkbenchTab}
        openTabs={workbenchTabs}
        onOpenTabsChange={setWorkbenchTabs}
        capabilityShortcuts={workbenchCapabilityShortcuts}
        onCopyText={desktopAdapters.writeText}
        onOpenExternalUrl={desktopAdapters.openExternalUrl}
        onClose={closeWorkbench}
        onRegisterFilesLifecycle={filesCapability.register}
        onRegisterTerminalLifecycle={workspaceChange.registerTerminal}
        onRegisterPreviewLifecycle={workspaceChange.registerPreview}
        onCloseFilesCapability={closeFilesCapability}
        onFilesCapabilityClosed={closeFilesCapability}
        onAddWorkspaceReference={addWorkspaceReference}
        workspaceReferencePreviewRequest={workspaceReferencePreviewRequest}
        onWorkspaceReferencePreviewSettled={settleWorkspaceReferencePreview}
        onGitBranchChange={publishGitBranch}
        attachmentTarget={attachmentPreviewTarget}
        attachmentPreviewPort={attachmentPreviewPort}
        onOpenAttachmentPreview={openAttachmentPreview}
        onDismissAttachment={dismissAttachmentPreview}
        onAttachmentRemoved={closeRemovedAttachmentPreview}
        onAttachmentsBound={bindAttachmentPreview}
        turnReviewTarget={turnReviewVisible ? openedTurnReview : undefined}
        retainedTurnReviewTarget={openedTurnReview}
        turnReviewScopeLabel={retainedTurnReviewLabel}
        requestedTurnReviewPath={requestedTurnReviewPath}
        requestedTurnReviewPathRevision={requestedTurnReviewPathRevision}
        latestTurnReviewAvailable={latestTurnReviewAvailable}
        turnReviewPort={turnReviewPort}
        onShowRetainedTurnReview={showRetainedTurnReview}
        onShowLatestTurnReview={() => {
          openLatestTurnReview();
        }}
        onDismissTurnReview={dismissTurnReview}
        planGoalAvailable={planGoalAvailable}
        goal={goal}
      />
    );

  return (
    <div className="ja-shell" data-app-ready="true">
      {settings.synchronizing ? (
        <span className="ja-visually-hidden" role="status" aria-live="polite">
          正在同步当前项目设置…
        </span>
      ) : null}
      <AppTitlebar
        platform={platform}
        sidebarOpen={sidebarVisible}
        showSidebarToggle={!required}
        onToggleSidebar={toggleSidebar}
        canGoBack={pageNavigation.canGoBack}
        canGoForward={pageNavigation.canGoForward}
        onBack={goBack}
        onForward={goForward}
        windowFrame={windowFrame.frame}
        windowActionPending={windowFrame.pendingAction}
        onWindowAction={windowFrame.invoke}
      />
      <div
        className={`ja-layout${settingsVisible ? " is-settings" : ""}${required ? " is-required-settings" : ""}${!inspectorOpen ? " is-inspector-hidden" : ""}${!sidebarVisible ? " is-sidebar-hidden" : ""}${compactNavigation ? " is-compact-navigation" : ""}`}
        style={{ "--ja-sidebar-ratio": `${sidebarPreviewRatio}%` } as CSSProperties}
      >
        {settingsVisible || !compactNavigation || !sidebarVisible ? null : (
          <button
            type="button"
            className="ja-navigation-backdrop"
            aria-label="关闭导航栏"
            onClick={closeSidebar}
          />
        )}
        {settingsVisible || !sidebarVisible ? null : (
          <div className={`ja-navigation-shell${compactNavigation ? " is-compact" : ""}`}>
            <NavigationSidebar
              projects={workspace.projects}
              projectCatalogLoading={workspace.catalogLoading}
              projectCatalogError={workspace.catalogError}
              currentWorkspaceId={isProjectScope ? workspace.workspace?.workspaceId : undefined}
              generalWorkspaceSelected={workspace.workspace?.kind === "general"}
              projectSectionCollapsed={projectSectionCollapsed}
              historySectionCollapsed={historySectionCollapsed}
              runtimeLabel={runtimeLabel(boot.status)}
              runtimeTone={runtimeTone(boot.status)}
              runtimeIssueReason={runtimeIssueReason}
              runtimeIssueContent={runtimeIssue}
              currentThreadId={conversation.currentThreadId}
              threads={conversation.threads}
              historyBusy={conversation.busy && conversation.threads.length === 0}
              historyError={conversation.error}
              newConversationDisabled={!conversationScopeReady}
              projectBusy={
                workspace.busy || settings.synchronizing || boot.status === "recovery_required"
              }
              compact={compactNavigation}
              platform={platform}
              activeAction={settingsVisible ? "settings" : "workspace"}
              conversationSearchOpen={conversationSearchOpen}
              onNewConversation={createConversation}
              onSelectConversation={selectConversation}
              onOpenConversationSearch={openConversationSearch}
              onRenameConversation={conversation.rename}
              onPinConversation={pinConversation}
              onArchiveConversation={archiveConversation}
              mutatingThreadIds={conversation.mutatingThreadIds}
              onChooseProject={chooseProject}
              onSelectGeneral={selectGeneral}
              onSelectProject={selectProject}
              onProjectSectionCollapsedChange={setProjectSectionCollapsed}
              onHistorySectionCollapsedChange={setHistorySectionCollapsed}
              onRetryProjects={workspace.retryCatalog}
              onOpenSettings={openSettings}
              onRequestClose={closeSidebar}
            />
            {compactNavigation ? null : (
              <NavigationResizeHandle
                ratio={sidebarPreviewRatio}
                minRatio={SIDEBAR_RATIO_MIN}
                maxRatio={SIDEBAR_RATIO_MAX}
                onPreview={setSidebarPreviewRatio}
                onCommit={commitSidebarRatio}
              />
            )}
          </div>
        )}
        <div className="ja-workspace-stage">
          {settingsVisible ? (
            <main className="ja-main ja-settings-layer">{settingsMainView}</main>
          ) : null}
          <div
            id="ja-workspace-layout"
            className="ja-workspace-panels"
            data-layout-mode={
              workbenchVisible
                ? singlePaneWorkbench
                  ? "workbench-drawer"
                  : "split"
                : "conversation"
            }
            style={{ "--ja-workbench-size": `${workbenchSize}%` } as CSSProperties}
            hidden={settingsVisible}
            aria-hidden={settingsVisible || undefined}
          >
            <div
              id="conversation"
              className="ja-workspace-panel"
              hidden={singlePaneWorkbench && workbenchVisible}
              aria-hidden={(singlePaneWorkbench && workbenchVisible) || undefined}
            >
              <main className="ja-main">{workspaceMainView}</main>
            </div>
            {workbenchVisible && !singlePaneWorkbench ? (
              <WorkbenchResizeHandle
                size={workbenchSize}
                minSize={WORKBENCH_SIZE_MIN}
                maxSize={WORKBENCH_SIZE_MAX}
                onPreview={setWorkbenchPreviewSize}
                onCommit={commitWorkbenchSize}
              />
            ) : null}
            <div
              id="workbench"
              className="ja-workspace-panel"
              hidden={!workbenchVisible}
              aria-hidden={!workbenchVisible || undefined}
            >
              {inspectorContent}
            </div>
          </div>
        </div>
      </div>
      <CommandPalette
        open={commandPaletteOpen}
        viewModel={commandPalette.viewModel}
        actions={commandPalette.actions}
      />
      <ConversationSearchDialog
        open={conversationSearchOpen}
        shortcutLabel={navigationShortcut("search-conversations", platform).display}
        refreshIdentity={lastThreadMetadataEvent?.params.eventId}
        onOpenChange={setConversationSearchOpen}
        onSearch={conversation.search}
        onSelect={selectConversation}
        onRestore={restoreConversation}
      />
    </div>
  );
}
