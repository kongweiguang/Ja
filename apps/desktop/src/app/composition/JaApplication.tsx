// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  useCallback,
  useEffect,
  useMemo,
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
  isWorkItem,
  itemChangedFiles,
  itemDiffStat,
  selectItemsForThread,
  turnDurationMs,
  turnStatusLabel,
  useConversationController,
  useTimelineStore,
  type ConversationArtifactPort,
  type ConversationAttachmentPort,
  type ConversationSummary,
  type TimelineItemAdapter,
  type TimelineTurn as Turn,
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
import { WorkbenchResizeHandle, type WorkbenchTab } from "@/features/workbench";
import type { HistoryAdapter } from "@/api/tauri/history";
import {
  SIDEBAR_RATIO_MAX,
  SIDEBAR_RATIO_MIN,
  WORKBENCH_SIZE_MAX,
  WORKBENCH_SIZE_MIN,
  useUiPreferencesStore,
} from "@/shared/preferences/uiPreferences";
import { useShallow } from "zustand/react/shallow";
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
import { RecoveryPanel } from "./RecoveryPanel";
import { SettingsView } from "./SettingsView";
import { WorkbenchHost } from "./WorkbenchHost";
import { ConversationWorkspace } from "./ConversationWorkspace";
import {
  DEFAULT_DESKTOP_INTEGRATIONS,
  DEFAULT_ATTACHMENT_PORT,
  DEFAULT_CONVERSATION_ARTIFACT_PORT,
  DEFAULT_HISTORY_ADAPTER,
  DEFAULT_SETTINGS_ADAPTER,
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
 * 把当前 Thread projection 归约为会话摘要。只聚合 runtime/Git metadata 已提供的值；
 * 缺失指标继续缺失，不能用零伪造证据。
 */
function buildWorkbenchSummary({
  scope,
  model,
  runtime,
  items,
  turns,
  activeStatus,
}: {
  scope: string;
  model?: string;
  runtime: string;
  items: readonly TimelineItemAdapter[];
  turns: readonly Turn[];
  activeStatus?: Turn["status"];
}): ConversationSummary {
  const changedFileMetrics = items
    .map(itemChangedFiles)
    .filter((value): value is number => value !== undefined);
  const diffMetrics = items
    .map(itemDiffStat)
    .filter((value): value is NonNullable<ReturnType<typeof itemDiffStat>> => value !== undefined);
  const durations = turns
    .map(turnDurationMs)
    .filter((value): value is number => value !== undefined);
  const durationMs =
    durations.length === 0 ? undefined : durations.reduce((total, value) => total + value, 0);
  const additions = diffMetrics.some((value) => value.additions !== undefined)
    ? diffMetrics.reduce((total, value) => total + (value.additions ?? 0), 0)
    : undefined;
  const deletions = diffMetrics.some((value) => value.deletions !== undefined)
    ? diffMetrics.reduce((total, value) => total + (value.deletions ?? 0), 0)
    : undefined;
  return {
    scope,
    model,
    runtime,
    status: activeStatus === undefined ? undefined : turnStatusLabel(activeStatus),
    turnCount: turns.length,
    stepCount: items.filter(isWorkItem).length,
    changedFiles:
      changedFileMetrics.length === 0
        ? undefined
        : changedFileMetrics.reduce((total, value) => total + value, 0),
    additions,
    deletions,
    durationMs,
  };
}

export interface JaApplicationProps {
  readonly settingsAdapter?: SettingsAdapter;
  readonly projectPicker?: WorkspacePickerPort;
  readonly historyAdapter?: HistoryAdapter;
  readonly attachmentPort?: ConversationAttachmentPort;
  readonly conversationArtifactPort?: ConversationArtifactPort;
  readonly workbenchAdapters?: JaWorkbenchAdapters;
  readonly desktopAdapters?: DesktopIntegrationAdapters;
  readonly navigationAdapters?: NavigationNativeAdapters;
}

const APP_EXIT_OBSERVER = { observe: observeAppExitRequested };

/**
 * 在 composition root 组合 native adapter 与 Files/Terminal 生命周期，不把资源状态提升到壳层；
 * runtimeState 的正 generation 是 Review 事件的 admission fence，缺失时 Review 只读且不接受失效刷新。
 */
export function JaApplication({
  settingsAdapter,
  projectPicker,
  historyAdapter,
  attachmentPort = DEFAULT_ATTACHMENT_PORT,
  conversationArtifactPort = DEFAULT_CONVERSATION_ARTIFACT_PORT,
  workbenchAdapters,
  desktopAdapters = DEFAULT_DESKTOP_INTEGRATIONS,
  navigationAdapters = DEFAULT_NAVIGATION_NATIVE_ADAPTERS,
}: JaApplicationProps): ReactElement {
  const { boot, runtimeState, turnAdmissionReady, lastEvent, lastThreadMetadataEvent } =
    useRuntimeState();
  const { startRuntime, generalWorkspace, queryRuntime } = useRuntimeLifecycle();
  const resolvedWorkbenchAdapters = workbenchAdapters ?? DEFAULT_WORKBENCH_ADAPTERS;
  const [conversationFocusAvailable, setConversationFocusAvailable] = useState(false);
  const [conversationSearchOpen, setConversationSearchOpen] = useState(false);
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
  const reduceMotion = useUiPreferencesStore((state) => state.reduceMotion);
  const highContrast = useUiPreferencesStore((state) => state.highContrast);
  const setThemeMode = useUiPreferencesStore((state) => state.setThemeMode);
  const setHighContrast = useUiPreferencesStore((state) => state.setHighContrast);
  const setReduceMotion = useUiPreferencesStore((state) => state.setReduceMotion);
  const appearancePort = useMemo<SettingsAppearancePort>(
    () => ({
      reducedMotion: reduceMotion,
      highContrast,
      setThemeMode,
      setHighContrast,
      setReduceMotion,
    }),
    [highContrast, reduceMotion, setHighContrast, setReduceMotion, setThemeMode],
  );
  /**
   * composition 只在此处把通用 Runtime query 适配为 Settings 领域能力，
   * 从而让 feature application 不认识 JA-RPC method 或 params envelope。
   */
  const settingsRuntimePort = useMemo<SettingsRuntimePort>(
    () => ({
      listSkills: () => queryRuntime("skill/list", {}),
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
    configurationChange: projectSettingsConfigurationChange(lastEvent),
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
    history: historyAdapter ?? DEFAULT_HISTORY_ADAPTER,
    picker: projectPicker ?? DEFAULT_WORKSPACE_PICKER,
    generalWorkspace,
    runtimeState,
    configurationReady: activeModel !== undefined && settings.scopeReady,
    beforeWorkspaceChange: workspaceChange.beforeChange,
    onWorkspaceCommitted: setSettingsWorkspaceScope,
  });
  const currentGitBranch =
    gitBranchProjection !== undefined &&
    gitBranchProjection.workspaceId === workspace.workspace?.workspaceId
      ? gitBranchProjection.branch
      : undefined;
  const conversation = useConversationController({
    history: historyAdapter ?? DEFAULT_HISTORY_ADAPTER,
    workspace: workspace.workspace,
    workspaceRevision: workspace.revision,
    modelSelection: settings.snapshot.defaultSelection ?? undefined,
    accessMode: settings.snapshot.defaultAccessMode,
    runtimeState,
    metadataEvent: lastThreadMetadataEvent,
    activateWorkspace: workspace.activateForConversation,
  });
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
  const required = activeModel === undefined;
  const pageNavigation = usePageNavigationController(required);
  const { settingsVisible, settingsSection, setSettingsSection, navigate, goBack, goForward } =
    pageNavigation;
  const isProjectScope = workspace.workspace?.kind === "project";
  const summaryThreadId = conversation.currentThreadId ?? "";
  const summaryItems = useTimelineStore(
    useShallow((state) =>
      summaryThreadId === "" ? [] : selectItemsForThread(summaryThreadId)(state),
    ),
  ) as TimelineItemAdapter[];
  const summaryTurns = useTimelineStore(
    useShallow((state) =>
      summaryThreadId === ""
        ? []
        : Object.values(state.turns).filter((turn) => turn.threadId === summaryThreadId),
    ),
  ) as Turn[];
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
  const workbenchSummary = useMemo(
    () =>
      buildWorkbenchSummary({
        scope: isProjectScope ? (workspace.workspace?.displayName ?? "当前项目") : "无项目对话",
        model:
          activeModel === undefined
            ? undefined
            : `${activeModel.provider.name} · ${activeModel.model.name}`,
        runtime: runtimeLabel(boot.status),
        items: summaryItems,
        turns: summaryTurns,
        activeStatus: summaryTurns.find(
          (turn) => !["completed", "failed", "cancelled"].includes(turn.status),
        )?.status,
      }),
    [
      boot.status,
      isProjectScope,
      activeModel,
      summaryItems,
      summaryTurns,
      workspace.workspace?.displayName,
    ],
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

  /** Workbench 内的关闭按钮属于抽屉自身控制，只关闭布局而不伪造会话聚焦成功。 */
  const closeWorkbench = useCallback((): void => {
    setInspectorOpen(false);
  }, [setInspectorOpen]);

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
    (tab: WorkbenchTab): boolean => {
      if (!workspaceShortcutCapabilitiesEnabled || !inspectorOpen) return false;
      setWorkbenchTab(tab);
      return true;
    },
    [inspectorOpen, setWorkbenchTab, workspaceShortcutCapabilitiesEnabled],
  );

  const workbenchCapabilityShortcuts = useMemo<Partial<Record<WorkbenchTab, string>>>(
    () => ({
      review: navigationShortcut("open-review", platform).display,
      files: navigationShortcut("open-files", platform).display,
      terminal: navigationShortcut("open-terminal", platform).display,
      preview: navigationShortcut("open-preview", platform).display,
    }),
    [platform],
  );

  /** 从 Settings 或紧凑导航触发时先创建持久 Thread，再显示它。 */
  const createConversation = useCallback(async (): Promise<void> => {
    await conversation.create();
    navigate("workspace");
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

  /** 从 Settings 返回前恢复持久 Thread，避免会话闪现旧内容。 */
  const selectConversation = useCallback(
    async (threadId: string): Promise<void> => {
      await conversation.select(threadId);
      navigate("workspace");
    },
    [conversation, navigate],
  );

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

  const runtimeMainView: ReactElement | undefined =
    boot.status === "failed" || boot.status === "degraded" ? (
      <section className="ja-error-state" role="alert">
        <h1>本地运行时启动失败</h1>
        <p>{boot.message}</p>
        <Button type="button" variant="secondary" onClick={retryRuntime}>
          重新启动
        </Button>
      </section>
    ) : boot.status === "recovery_required" ? (
      <RecoveryPanel />
    ) : boot.status === "idle" || boot.status === "connecting" || boot.status === "stopped" ? (
      <section className="ja-loading-state" role="status">
        正在启动本地运行时…
      </section>
    ) : undefined;
  const settingsMainView =
    runtimeMainView ??
    (settings.loading ? (
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
        onOpenConversation={() => navigate("workspace")}
        desktopNotifications={desktopNotificationPreference}
      />
    ));
  const workspaceMainView =
    !settingsVisible && runtimeMainView !== undefined ? (
      runtimeMainView
    ) : settings.loading ? (
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
      <ConversationWorkspace
        workspace={workspace}
        conversation={conversation}
        settings={settings}
        inspectorOpen={inspectorOpen}
        onToggleInspector={toggleInspector}
        summary={workbenchSummary}
        workspaceAdapter={resolvedWorkbenchAdapters.workspace}
        gitBranch={currentGitBranch}
        onOpenLink={desktopAdapters.openExternalUrl}
        onCopyText={desktopAdapters.writeText}
        onConversationFocusAvailabilityChange={setConversationFocusAvailable}
        attachmentPort={attachmentPort}
        artifactPort={conversationArtifactPort}
      />
    );
  const inspectorContent =
    workspace.workspace === undefined ? null : (
      <WorkbenchHost
        key={workspace.workspace.workspaceId}
        workspace={workspace.workspace}
        generation={runtimeState?.generation}
        adapters={resolvedWorkbenchAdapters}
        active={workbenchVisible}
        selectedTab={workbenchTab}
        onTabChange={setWorkbenchTab}
        openTabs={workbenchTabs}
        onOpenTabsChange={setWorkbenchTabs}
        capabilityShortcuts={workbenchCapabilityShortcuts}
        onCopyText={desktopAdapters.writeText}
        onOpenExternalUrl={desktopAdapters.openExternalUrl}
        onClose={closeWorkbench}
        onRegisterFilesLifecycle={filesCapability.register}
        onRegisterTerminalLifecycle={workspaceChange.registerTerminal}
        onRegisterPreviewLifecycle={workspaceChange.registerPreview}
        onCloseFilesCapability={filesCapability.closeCapability}
        onGitBranchChange={publishGitBranch}
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
              currentThreadId={conversation.currentThreadId}
              threads={conversation.threads}
              historyBusy={conversation.busy}
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
              onOpenConversationSearch={() => setConversationSearchOpen(true)}
              onRenameConversation={conversation.rename}
              onChooseProject={chooseProject}
              onSelectGeneral={selectGeneral}
              onSelectProject={selectProject}
              onProjectSectionCollapsedChange={setProjectSectionCollapsed}
              onHistorySectionCollapsedChange={setHistorySectionCollapsed}
              onRetryProjects={workspace.retryCatalog}
              onOpenSettings={() => navigate("settings")}
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
          ) : (
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
          )}
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
      />
    </div>
  );
}
