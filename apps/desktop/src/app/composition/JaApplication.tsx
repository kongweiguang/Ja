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
import { ArrowLeft, Database } from "lucide-react";
import { useRuntimeLifecycle, useRuntimeState } from "../RuntimeProvider";
import type { JaWorkbenchAdapters } from "../useJaWorkbench";
import { Button } from "@/shared/ui/primitives/Button";
import {
  DataStorageSection,
  useSettingsController,
  type DesktopNotificationPreference,
  type DataStorageProps,
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
  readFullAnswerContent,
  conversationMarkdownFilename,
  exportConversationMarkdown,
  downloadPublicMarkdown,
  exportPublicMarkdown,
  useTimelineStore,
  type ConversationArtifactPort,
  type ConversationAttachmentPort,
  type ConversationMcpReader,
  type ConversationProcessBinding,
  type ConversationProcessPort,
  type ComposerSlashCommand,
  type ConversationSummary,
} from "@/features/conversation";
import type {
  ConversationModelOption,
  ConversationModelSelection,
  ConversationUsageReader,
} from "@/features/conversation";
import {
  AppTitlebar,
  ConversationSearchDialog,
  NavigationResizeHandle,
  NavigationSidebar,
  ProjectEditDialog,
  type ProjectEditInput,
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
import { createProcessSessionAdapter, type ProcessSessionAdapter } from "@/api/tauri/process";
import type {
  TaskComposerSkillSuggestion,
  TaskSummary,
  TaskThreadRenamePort,
  TaskTranscriptPort,
  TaskPreferencesPort,
} from "@/features/tasks";
import type { AttachmentPreviewPort, AttachmentPreviewTarget } from "@/features/workbench/preview";
import type { TurnReviewPort, TurnReviewTarget } from "@/features/workbench/review";
import { usePlanController, type PlanPort } from "@/features/plans";
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
import { useInterfacePreferences } from "../application/useInterfacePreferences";
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
import {
  ThreadWorkbenchSessions,
  type FilesCapabilityCloseFocusCapture,
  type RetainedWorkspaceRebindLifecycle,
} from "./ThreadWorkbenchSessions";
import { ProjectRebindDialog, type ProjectRebindConfirmation } from "./ProjectRebindDialog";
import { useThreadWorkbenchState } from "../application/useThreadWorkbenchState";
import {
  ConversationWorkspace,
  type ComposerWorkspaceReferenceTarget,
  type WorkspaceReferencePreviewOutcome,
  type WorkspaceReferencePreviewRequest,
  type ConversationOpenTargetOutcome,
  type ConversationOpenTargetRequest,
} from "./ConversationWorkspace";
import {
  DEFAULT_DESKTOP_INTEGRATIONS,
  DEFAULT_ATTACHMENT_PORT,
  DEFAULT_ATTACHMENT_PREVIEW_PORT,
  DEFAULT_CONVERSATION_ARTIFACT_PORT,
  DEFAULT_HISTORY_ADAPTER,
  DEFAULT_PLAN_PORT,
  DEFAULT_INTERACTION_PORT,
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
import type { RuntimeStorageInfo } from "../application/runtimePorts";
import { canRetryStorageRecovery, storageRecoveryMessage } from "@/shared/runtime/storageRecovery";

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

/** 只定位主正文 Composer，避免保留的侧聊 Composer 接管返回焦点。 */
function currentConversationComposer(): HTMLTextAreaElement | null {
  return document.querySelector<HTMLTextAreaElement>('#conversation [aria-label="消息"]');
}

/** 只允许精确 aria-controls 配对的当前菜单穿过 Radix modal 对应用根添加的 aria-hidden。 */
function activeWorkbenchPortalTrigger(): HTMLButtonElement | null {
  const workbench = document.querySelector<HTMLElement>("#workbench");
  const activeElement = document.activeElement;
  if (
    workbench === null ||
    workbench.closest<HTMLElement>("[hidden], [inert]") !== null ||
    !(activeElement instanceof HTMLElement)
  )
    return null;

  const menu = activeElement.closest<HTMLElement>(".ja-workbench-add-menu[role='menu']");
  if (
    menu?.id === undefined ||
    menu.closest<HTMLElement>('[hidden], [aria-hidden="true"], [inert]') !== null
  )
    return null;

  return (
    Array.from(workbench.querySelectorAll<HTMLButtonElement>("button[aria-controls]")).find(
      (trigger) =>
        trigger.getAttribute("aria-controls") === menu.id &&
        trigger.closest<HTMLElement>("[hidden], [inert]") === null,
    ) ?? null
  );
}

/**
 * 在会话导航提交后查询当前主正文 Composer；关闭 Workbench 时仅接受 body、预迁出的标题栏
 * 节点或与当前 portal 菜单配对的触发器，避免迟到回调接管用户后来选择的控件。
 */
function focusConversationComposerAfterNavigation(
  expectedFocus?: HTMLElement,
  isCurrentScope: () => boolean = () => true,
  allowedRestoreFocus?: HTMLElement,
): void {
  /** 查询执行时的当前节点，避免 Thread 切换后把焦点交给已经卸载的旧 Composer。 */
  const focusComposer = (): void => {
    if (!isCurrentScope()) return;
    const activeElement = document.activeElement;
    if (
      (expectedFocus !== undefined || allowedRestoreFocus !== undefined) &&
      activeElement !== expectedFocus &&
      activeElement !== allowedRestoreFocus &&
      activeElement !== document.body
    )
      return;
    const composer = currentConversationComposer();
    if (composer?.isConnected && !composer.disabled) composer.focus();
  };
  if (typeof window.requestAnimationFrame === "function")
    window.requestAnimationFrame(focusComposer);
  else focusComposer();
}

/** 隐藏可见 Workbench 前迁出其焦点；只有精确配对的 portal 菜单可越过 modal aria-hidden。 */
function moveVisibleWorkbenchFocusToTitlebar(): HTMLElement | null {
  const workbench = document.querySelector<HTMLElement>("#workbench");
  const activeElement = document.activeElement;
  const portalTrigger = activeWorkbenchPortalTrigger();
  if (
    !(activeElement instanceof HTMLElement) ||
    workbench === null ||
    (!workbench.contains(activeElement) && portalTrigger === null) ||
    activeElement.closest<HTMLElement>("[hidden], [inert]") !== null ||
    (portalTrigger === null && activeElement.closest<HTMLElement>('[aria-hidden="true"]') !== null)
  )
    return null;

  const target = document.querySelector<HTMLButtonElement>(
    ".ja-titlebar button.ja-titlebar-button:not(:disabled)",
  );
  if (
    target === null ||
    workbench.contains(target) ||
    target.closest<HTMLElement>('[hidden], [aria-hidden="true"], [inert]') !== null
  ) {
    activeElement.blur();
    return document.activeElement === document.body ? document.body : null;
  }
  target.focus({ preventScroll: true });
  if (document.activeElement === target) return target;
  activeElement.blur();
  return document.activeElement === document.body ? document.body : null;
}

/** 从 Workbench 返回时优先恢复当前正文中的对象来源；来源失效则回到主 Composer。 */
function restoreConversationObjectFocus(
  source: HTMLElement | null,
  expectedFocus?: HTMLElement,
  isCurrentScope: () => boolean = () => true,
  allowedRestoreFocus?: HTMLElement,
): void {
  const restoreFocus = (): void => {
    if (!isCurrentScope()) return;
    const activeElement = document.activeElement;
    if (
      (expectedFocus !== undefined || allowedRestoreFocus !== undefined) &&
      activeElement !== expectedFocus &&
      activeElement !== allowedRestoreFocus &&
      activeElement !== document.body
    )
      return;
    if (
      source?.isConnected &&
      source.closest<HTMLElement>('[hidden], [aria-hidden="true"], [inert]') === null
    ) {
      source.focus();
      return;
    }
    const composer = currentConversationComposer();
    if (
      composer?.isConnected &&
      !composer.disabled &&
      composer.closest<HTMLElement>('[hidden], [aria-hidden="true"], [inert]') === null
    )
      composer.focus();
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
  readonly planPort?: PlanPort;
  readonly processAdapter?: ProcessSessionAdapter;
}

const APP_EXIT_OBSERVER = { observe: observeAppExitRequested };
const DEFAULT_TASK_ADAPTER = createTaskAdapter();
const DEFAULT_PROCESS_SESSION_ADAPTER = createProcessSessionAdapter();

/**
 * Composition root 组合 native adapter 与 Files/Terminal 生命周期，不提升资源状态；设置页只隐藏并暂停工作区，保留 Editor/xterm 与 PTY 所有权。
 * 右栏目标按 Thread 隔离；Review 缺少 Runtime generation 时只读。首屏保留对话，项目操作等待配置 scope，运行时故障提供重连和人工恢复。
 * 数据恢复不依赖配置读取；Settings trust 写入服务端 Workspace，不切换 Thread 或重建 Turn。
 * 历史读取失败只向导航传递空态门控，提示与重试仍由当前 Conversation 的 scoped Notification 持有。
 * @author kongweiguang
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
  planPort = DEFAULT_PLAN_PORT,
  processAdapter = DEFAULT_PROCESS_SESSION_ADAPTER,
}: JaApplicationProps): ReactElement {
  const workspaceReferenceTargetRef = useRef<
    ((reference: ComposerWorkspaceReferenceTarget) => void) | undefined
  >(undefined);
  type SideChatLauncher = (content?: string) => Promise<void>;
  const sideChatLauncherRef = useRef<SideChatLauncher | undefined>(undefined);
  const [sideChatLauncherReady, setSideChatLauncherReady] = useState(false);
  /** 当前 Thread 的侧聊入口由 WorkbenchHost 注册；主 Composer 只转发 slash 参数，不复制创建逻辑。 */
  const registerSideChatLauncher = useCallback((launcher: SideChatLauncher | undefined): void => {
    sideChatLauncherRef.current = launcher;
    setSideChatLauncherReady(launcher !== undefined);
  }, []);
  /** `/btw` 不自动唤醒目标外会话；没有当前 Host 时以可重试错误结束命令。 */
  const requestSideChat = useCallback((content?: string): Promise<void> => {
    const launcher = sideChatLauncherRef.current;
    return launcher === undefined
      ? Promise.reject(new Error("侧聊暂不可用，请稍后重试。"))
      : launcher(content);
  }, []);
  const {
    boot,
    runtimeState,
    turnAdmissionReady,
    lastConfigurationEvent,
    lastThreadMetadataEvent,
    lastWorkspaceMetadataEvent,
  } = useRuntimeState();
  const {
    startRuntime,
    activateWorkspace,
    queryRuntime,
    readRuntimeStorage,
    openStorageBackup,
    retryStorage,
  } = useRuntimeLifecycle();
  const [reconnectPending, setReconnectPending] = useState(false);
  const reconnectInFlightRef = useRef(false);
  const reconnectAvailable =
    ["stopped", "failed", "degraded"].includes(boot.status) &&
    runtimeState?.status !== "busy" &&
    runtimeState?.status !== "incompatible" &&
    runtimeState?.status !== "recovery_required" &&
    (runtimeState?.storageRecovery === undefined || runtimeState.storageRecovery.phase === "ready");
  /**
   * 重连只推进既有生命周期 lane；ref 在状态提交前去重，存储与人工恢复仍保留各自入口。
   * 成功后只在原按钮失去焦点时归还当前页面的焦点，不能夺走等待期间用户选择的新目标。
   */
  const reconnectRuntime = useCallback(async (): Promise<void> => {
    if (reconnectInFlightRef.current || !reconnectAvailable) return;
    reconnectInFlightRef.current = true;
    setReconnectPending(true);
    const trigger = document.activeElement;
    try {
      const status = await startRuntime();
      toast.dismiss("ja-runtime-reconnect");
      if (status.storageRecovery !== undefined && status.storageRecovery.phase !== "ready") return;
      /** 查询已提交的当前页面，避免等待期间导航后聚焦隐藏的 Composer 或旧设置分类。 */
      const restoreFocus = (): void => {
        if (document.activeElement !== document.body && document.activeElement !== trigger) return;
        const selectedTab = document.querySelector<HTMLButtonElement>(
          '.ja-settings-view [role="tab"][aria-selected="true"]',
        );
        const composer = document.querySelector<HTMLTextAreaElement>('[aria-label="消息"]');
        const target =
          selectedTab ??
          (composer?.isConnected && !composer.disabled ? composer : undefined) ??
          document.querySelector<HTMLButtonElement>(".ja-titlebar button:not(:disabled)");
        target?.focus();
      };
      if (typeof window.requestAnimationFrame === "function")
        window.requestAnimationFrame(restoreFocus);
      else queueMicrotask(restoreFocus);
    } catch {
      toast.error("重新连接失败，请稍后重试。", { id: "ja-runtime-reconnect" });
    } finally {
      reconnectInFlightRef.current = false;
      setReconnectPending(false);
    }
  }, [reconnectAvailable, startRuntime]);
  const [runtimeStorage, setRuntimeStorage] = useState<RuntimeStorageInfo>();
  const [storageReadError, setStorageReadError] = useState<string>();
  /** 快速打开时延迟显示短暂提醒，避免闪烁；会话和草稿保持挂载状态。 */
  useEffect(() => {
    if (boot.status !== "connecting") {
      toast.dismiss("ja-storage-opening");
      return;
    }
    const timer = setTimeout(() => {
      const recovery = runtimeState?.storageRecovery;
      toast.loading(
        recovery?.phase === "rebuilding" ? storageRecoveryMessage(recovery) : "正在准备本地数据…",
        { id: "ja-storage-opening" },
      );
    }, 600);
    return () => {
      clearTimeout(timer);
      toast.dismiss("ja-storage-opening");
    };
  }, [boot.status, runtimeState?.storageRecovery]);
  const storageGeneration = useRef(runtimeState?.generation);
  storageGeneration.current = runtimeState?.generation;
  /** Runtime 被替换后丢弃迟到的存储读取结果，与会话投影采用相同处理。 */
  const reloadStorage = useCallback(async (): Promise<void> => {
    const generation = runtimeState?.generation;
    try {
      const info = await readRuntimeStorage();
      if (storageGeneration.current !== generation) return;
      setRuntimeStorage(info);
      setStorageReadError(undefined);
    } catch (error) {
      if (storageGeneration.current === generation) setStorageReadError("存储信息读取失败，请重试");
      throw error;
    }
  }, [readRuntimeStorage, runtimeState?.generation]);
  /** 阻断状态仍可由 native 读取目录投影；恢复身份优先取本代运行时事实。 */
  useEffect(() => {
    if (runtimeState?.storageRecovery !== undefined) {
      setRuntimeStorage((previous) =>
        previous === undefined
          ? previous
          : { ...previous, storageRecovery: runtimeState.storageRecovery },
      );
    }
    if (runtimeState?.status === "ready" || runtimeState?.storageRecovery?.phase === "blocked")
      void reloadStorage().catch(() => undefined);
  }, [runtimeState?.status, runtimeState?.storageRecovery, reloadStorage]);
  /** 即使重试失败，也要读取最新的恢复决策，确保显示的下一步操作准确。 */
  const retryLocalStorage = useCallback(async (): Promise<void> => {
    try {
      await retryStorage?.();
    } finally {
      await reloadStorage();
    }
  }, [retryStorage, reloadStorage]);
  const resolvedWorkbenchAdapters = workbenchAdapters ?? DEFAULT_WORKBENCH_ADAPTERS;
  const resolvedHistoryAdapter = historyAdapter ?? DEFAULT_HISTORY_ADAPTER;
  /** 持续进程使用独立的固定 command adapter；列表返回的 registry generation 由面板传回后续动作。 */
  const processSessionPort = useMemo<ConversationProcessPort>(
    () => ({
      list: (input) => processAdapter.list(input),
      read: (input) => processAdapter.read(input),
      write: (input) => processAdapter.write(input),
      stop: (input) => processAdapter.stop(input),
    }),
    [processAdapter],
  );
  /**
   * 将 History 所属者的单一只读方法收窄给 Composer，避免界面获得完整历史适配器或原始 IPC。
   * TauriHistoryAdapter 的实现依赖 `this.bridge`，因此必须在组合边界绑定所属实例，不能把类方法
   * 方法解构为裸函数后交给浮层调用。
   */
  const conversationUsageReader = useMemo<ConversationUsageReader | undefined>(() => {
    const read = resolvedHistoryAdapter.threadUsageRead;
    return read === undefined ? undefined : { read: read.bind(resolvedHistoryAdapter) };
  }, [resolvedHistoryAdapter]);
  /** MCP 状态读取器按 Thread 隔离并缓存，避免流式渲染期间重复读取已打开的弹出层。 */
  const conversationMcpReader = useMemo<ConversationMcpReader | undefined>(() => {
    const read = resolvedHistoryAdapter.threadMcpRead;
    if (read === undefined) return undefined;
    /** 通过所属适配器绑定方法，因为生产环境的历史读取方法使用其原生桥接对象。 */
    const readThreadMcp = (threadId: string) => read.call(resolvedHistoryAdapter, { threadId });
    return { read: readThreadMcp };
  }, [resolvedHistoryAdapter]);
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
      /** Child 的正文观察独立于 Task Activity handle，隐藏 Tab 时可单独释放。 */
      observe: (input) => resolvedHistoryAdapter.threadObserve(input),
      unobserve: (input) => resolvedHistoryAdapter.threadUnobserve(input),
      /** Child Transcript 原样复用完整 thread/read 快照，确保 Timeline reducer 不丢队列与 Usage。 */
      read: (input) => resolvedHistoryAdapter.threadRead(input),
    }),
    [resolvedHistoryAdapter],
  );
  /** 子任务正文进入共享 TimelineStore，按 Thread/Item identity 缓存并校验分页边界。 */
  const readTaskMessageContent = useCallback(
    (threadId: string, itemId: string): Promise<string> =>
      useTimelineStore.getState().hydrateItemContent(
        threadId,
        itemId,
        (input) => resolvedHistoryAdapter.itemContentRead(input),
        () => useTimelineStore.getState().threads[threadId] !== undefined,
      ),
    [resolvedHistoryAdapter],
  );
  /** 子任务最终答复跨多个公开 Item 时，片段和组合结果都由共享 TimelineStore 缓存。 */
  const readTaskAnswerContent = useCallback(
    (
      threadId: string,
      finalItemId: string,
      minimumRevision?: number,
      turnId?: string,
    ): Promise<string> => {
      const isCurrent = () => useTimelineStore.getState().threads[threadId] !== undefined;
      return useTimelineStore
        .getState()
        .hydrateAnswerContent(
          threadId,
          finalItemId,
          () =>
            readFullAnswerContent(
              resolvedHistoryAdapter,
              threadId,
              finalItemId,
              isCurrent,
              minimumRevision,
              turnId,
              (itemThreadId, itemId, current) =>
                useTimelineStore
                  .getState()
                  .hydrateItemContent(
                    itemThreadId,
                    itemId,
                    (input) => resolvedHistoryAdapter.itemContentRead(input),
                    current,
                  ),
            ),
          isCurrent,
        );
    },
    [resolvedHistoryAdapter],
  );
  const taskPreferencesPort = useMemo<TaskPreferencesPort>(
    () => ({
      /** child 偏好直接复用唯一 History owner；调用方必须提供 child thread revision。 */
      update: async (input) => {
        const updated = await resolvedHistoryAdapter.threadPreferencesUpdate(input);
        if (updated.preferences === null) throw new Error("child preferences unavailable");
        return updated.preferences;
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
  const openTargetRequestSequenceRef = useRef(0);
  const openTargetSourceRef = useRef<
    | { requestId: number; workspaceId: string; threadId: string; source: HTMLElement | null }
    | undefined
  >(undefined);
  const attachmentPreviewSourceRef = useRef<HTMLButtonElement | null>(null);
  const [settingsWorkspaceScope, setSettingsWorkspaceScope] = useState<
    { workspaceId: string; kind: "project"; bindingKey: string } | undefined
  >();
  /** Settings v3 接收的 workspace scope 只表示项目覆盖；session 目录不携带项目配置。 */
  const publishSettingsWorkspaceScope = useCallback(
    (selected: WorkspaceProjection | undefined): void => {
      setSettingsWorkspaceScope(
        selected?.kind === "project"
          ? { workspaceId: selected.workspaceId, kind: "project", bindingKey: selected.rootPath }
          : undefined,
      );
    },
    [setSettingsWorkspaceScope],
  );
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
  const rebindLifecycleRef = useRef<RetainedWorkspaceRebindLifecycle | undefined>(undefined);
  const [rebindConfirmation, setRebindConfirmation] = useState<ProjectRebindConfirmation>();
  const rebindChoiceRef = useRef<((choice: "save" | "discard" | "cancel") => void) | undefined>(
    undefined,
  );
  const [editingProject, setEditingProject] = useState<WorkspaceProjection>();
  const projectEditIntentRef = useRef(0);
  const projectEditFocusRef = useRef<(() => void) | undefined>(undefined);
  const projectEditWorkspaceIdRef = useRef<string | undefined>(undefined);
  /** 每个保留 Thread 的生命周期由宿主注册，编辑另一个项目也不能清理当前项目资源。 */
  const registerRebindLifecycle = useCallback(
    (lifecycle: RetainedWorkspaceRebindLifecycle | undefined): void => {
      rebindLifecycleRef.current = lifecycle;
    },
    [],
  );
  /** 同一待决确认只结算一次；取消通过 AbortError 留住原编辑弹窗的输入。 */
  const chooseRebindDisposition = useCallback((choice: "save" | "discard" | "cancel"): void => {
    const settle = rebindChoiceRef.current;
    rebindChoiceRef.current = undefined;
    setRebindConfirmation(undefined);
    settle?.(choice);
  }, []);
  /** 项目更新的资源事务独立于页面切换，必须在 Java 写入前处理所有保留会话的草稿。 */
  const beforeProjectRebind = useCallback(async (workspaceId: string) => {
    const lifecycle = rebindLifecycleRef.current;
    if (lifecycle === undefined) return undefined;
    const documents = lifecycle.listUnsavedDocuments(workspaceId);
    let disposition: "save" | "discard" = "save";
    if (documents.length > 0) {
      if (rebindChoiceRef.current !== undefined)
        throw new Error("另一个项目正在等待文件处理，请先完成。");
      const choice = await new Promise<"save" | "discard" | "cancel">((resolve) => {
        rebindChoiceRef.current = resolve;
        setRebindConfirmation({ workspaceId, documents });
      });
      if (choice === "cancel") throw new DOMException("用户取消更改目录", "AbortError");
      disposition = choice;
    }
    return lifecycle.prepareWorkspaceRebind(workspaceId, disposition);
  }, []);
  useEffect(
    () => () => {
      rebindChoiceRef.current?.("cancel");
      rebindChoiceRef.current = undefined;
    },
    [],
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
      listMcpServers: (input) => queryRuntime("mcp/list", input ?? {}),
      testMcp: (mcpRevision, input) => queryRuntime("mcp/test", { ...input, mcpId: mcpRevision }),
      testModel: (providerId, modelId) => queryRuntime("model/test", { providerId, modelId }),
      discoverModels: (providerId) => queryRuntime("model/discover", { providerId }),
      listMcpTools: (mcpRevision, input) =>
        queryRuntime("mcp/list-tools", { ...input, mcpId: mcpRevision }),
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
  const taskModels = useMemo<readonly ConversationModelOption[]>(
    () =>
      settings.snapshot.providers.flatMap((provider) =>
        provider.models.map((model) => ({
          value: `${provider.providerId}:${model.modelId}`,
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
  /** 先复用原生目录解析核实物理目标，避免等价路径或无效输入提前关闭当前项目资源。 */
  const resolveProjectDirectory = useCallback(
    async (cwd: string): Promise<string> => {
      const target = await resolvedWorkbenchAdapters.preview.resolveFile(cwd);
      if (target.kind !== "directory") throw new Error("项目目录必须是一个现有文件夹。");
      return target.canonicalPath;
    },
    [resolvedWorkbenchAdapters.preview],
  );
  const workspace = useWorkspaceController({
    history: resolvedHistoryAdapter,
    picker: projectPicker ?? DEFAULT_WORKSPACE_PICKER,
    activateWorkspace,
    runtimeState,
    configurationReady: settings.scopeReady,
    beforeWorkspaceChange: workspaceChange.beforeChange,
    beforeWorkspaceRebind: beforeProjectRebind,
    resolveProjectDirectory,
    onWorkspaceCommitted: publishSettingsWorkspaceScope,
  });
  const workspaceReadProject = workspace.readProject;
  const workspaceUpdateProject = workspace.updateProject;
  const workspaceRecheckProjectBinding = workspace.recheckProjectBinding;
  const workspaceRefreshProjectMetadata = workspace.refreshProjectMetadata;
  /** 项目通知只携带版本，客户端回读权威资料；Thread Timeline 不接收项目元数据。 */
  useEffect(() => {
    if (lastWorkspaceMetadataEvent === undefined) return;
    const { workspaceId, revision } = lastWorkspaceMetadataEvent.params;
    void workspaceRefreshProjectMetadata(workspaceId, revision).catch(() => {
      toast.error("项目资料未能同步，请重新检查项目。", {
        id: `ja-workspace-metadata:${workspaceId}`,
      });
    });
  }, [lastWorkspaceMetadataEvent, workspaceRefreshProjectMetadata]);
  /** 菜单打开前读取数据库当前版本；另存 workspaceId 以便响应式重建后仍回到同一项目行。 @author kongweiguang */
  const editProject = useCallback(
    async (workspaceId: string, restoreFocus?: () => void): Promise<void> => {
      const intent = ++projectEditIntentRef.current;
      const source = document.activeElement;
      try {
        const project = await workspaceReadProject(workspaceId);
        if (intent !== projectEditIntentRef.current) return;
        projectEditFocusRef.current =
          restoreFocus ??
          (() => {
            if (source instanceof HTMLElement && source.isConnected) source.focus();
          });
        projectEditWorkspaceIdRef.current = workspaceId;
        setEditingProject(project);
      } catch {
        if (intent === projectEditIntentRef.current) toast.error("项目资料未能读取，请重试。");
      }
    },
    [workspaceReadProject],
  );
  /** 关闭时作废迟到的项目读取，焦点在 Dialog 卸载后的 autofocus 阶段恢复。 */
  const closeProjectEditor = useCallback((): void => {
    projectEditIntentRef.current += 1;
    setEditingProject(undefined);
  }, []);
  /** 优先恢复原入口；其卸载后按稳定项目身份查当前侧栏行，最后退回可见全局操作点。 @author kongweiguang */
  const restoreProjectEditorFocus = useCallback((): void => {
    projectEditFocusRef.current?.();
    const workspaceId = projectEditWorkspaceIdRef.current;
    projectEditWorkspaceIdRef.current = undefined;
    /** 仅把连通、可见且可用的控件视作已恢复，避免对响应式卸载或禁用节点误判成功。 @author kongweiguang */
    const canReceiveFocus = (target: Element | null): target is HTMLElement => {
      if (
        !(target instanceof HTMLElement) ||
        target === document.body ||
        !target.isConnected ||
        target.closest<HTMLElement>("[hidden], [inert]") !== null
      )
        return false;
      if (
        (target instanceof HTMLButtonElement || target instanceof HTMLTextAreaElement) &&
        target.disabled
      )
        return false;
      return typeof target.checkVisibility !== "function" || target.checkVisibility();
    };
    if (canReceiveFocus(document.activeElement)) return;

    /** workspaceId 是项目身份；在当前侧栏容器内查找可见实例，避免索引变化或同名项目误聚焦。 @author kongweiguang */
    const projectRow =
      workspaceId === undefined
        ? null
        : (Array.from(
            document.querySelectorAll<HTMLElement>(
              ".ja-navigation-project-list [data-workspace-id]",
            ),
          ).find((row) => row.dataset["workspaceId"] === workspaceId) ?? null);
    const currentProjectButton =
      projectRow?.querySelector<HTMLButtonElement>('button[data-scope-kind="project"]') ?? null;
    if (canReceiveFocus(currentProjectButton)) {
      currentProjectButton.focus({ preventScroll: true });
      if (document.activeElement === currentProjectButton) return;
    }

    const sidebarToggle = document.querySelector<HTMLButtonElement>(
      '.ja-titlebar button.ja-titlebar-button[aria-label="显示侧边栏"], .ja-titlebar button.ja-titlebar-button[aria-label="隐藏侧边栏"]',
    );
    const composer = currentConversationComposer();
    const fallback = canReceiveFocus(sidebarToggle)
      ? sidebarToggle
      : canReceiveFocus(composer)
        ? composer
        : undefined;
    fallback?.focus({ preventScroll: true });
  }, []);
  /** 保存结果交回发起它的 Dialog session；由其确认仍挂载后关闭，避免旧保存关掉后来打开的编辑器。 */
  const saveProject = useCallback(
    async (input: ProjectEditInput): Promise<void> => {
      await workspaceUpdateProject(input);
    },
    [workspaceUpdateProject],
  );
  /** 显式复核使用权威项目资料和绑定恢复；只更新仍属于本次编辑意图的 projection，草稿由 Dialog 保留。 */
  const recheckEditedProject = useCallback(
    async (workspaceId: string): Promise<WorkspaceProjection> => {
      const intent = projectEditIntentRef.current;
      const project = await workspaceRecheckProjectBinding(workspaceId);
      if (
        intent === projectEditIntentRef.current &&
        projectEditWorkspaceIdRef.current === workspaceId
      )
        setEditingProject(project);
      return project;
    },
    [workspaceRecheckProjectBinding],
  );
  /** 复制服务端登记的真实目录，不依赖该目录当前是否存在。 */
  const copyProjectPath = useCallback(
    async (workspaceId: string): Promise<void> => {
      const project = await workspaceReadProject(workspaceId);
      await desktopAdapters.writeText(project.rootPath);
    },
    [workspaceReadProject, desktopAdapters],
  );
  /** 原生会话入口须同时绑定当前 Runtime 与已恢复 Workspace；命令层仍执行服务端 fencing。 */
  const processSessionBinding = useMemo<ConversationProcessBinding | undefined>(() => {
    const workspaceId = workspace.workspace?.workspaceId;
    const generation = runtimeState?.generation;
    const status = runtimeState?.status;
    if (
      !workspace.bindingReady ||
      workspaceId === undefined ||
      generation === undefined ||
      (status !== "ready" && status !== "busy")
    )
      return undefined;
    return { workspaceId, generation, port: processSessionPort };
  }, [
    processSessionPort,
    workspace.bindingReady,
    runtimeState?.generation,
    runtimeState?.status,
    workspace.workspace?.workspaceId,
  ]);
  /** 暂时清空活动 Workspace 时保留最后一份服务端投影，供隐藏 Thread Host 完成资源清理。 */
  const [retainedWorkbenchWorkspace, setRetainedWorkbenchWorkspace] =
    useState<WorkspaceProjection>();
  const nextWorkbenchWorkspace = workspace.workspace;
  if (
    nextWorkbenchWorkspace !== undefined &&
    (retainedWorkbenchWorkspace?.workspaceId !== nextWorkbenchWorkspace.workspaceId ||
      retainedWorkbenchWorkspace.rootPath !== nextWorkbenchWorkspace.rootPath ||
      retainedWorkbenchWorkspace.kind !== nextWorkbenchWorkspace.kind ||
      retainedWorkbenchWorkspace.displayName !== nextWorkbenchWorkspace.displayName ||
      retainedWorkbenchWorkspace.trust !== nextWorkbenchWorkspace.trust ||
      retainedWorkbenchWorkspace.revision !== nextWorkbenchWorkspace.revision)
  ) {
    setRetainedWorkbenchWorkspace(nextWorkbenchWorkspace);
  }
  const workbenchHostWorkspace = nextWorkbenchWorkspace ?? retainedWorkbenchWorkspace;
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
  /** Explorer 从数据库登记目录解析，不需要选择项目或激活另一个项目的文件句柄。 */
  const openProjectFolder = useCallback(
    async (workspaceId: string): Promise<void> => {
      const project = await workspaceReadProject(workspaceId);
      await resolvedWorkbenchAdapters.preview.revealFile(project.rootPath);
    },
    [resolvedWorkbenchAdapters.preview, workspaceReadProject],
  );
  /** 文件夹菜单按 Thread 的服务端 workspace identity 打开 Explorer，不更改当前选中会话。 */
  const openThreadWorkspaceFolder = useCallback(
    async (threadId: string): Promise<void> => {
      const thread = conversation.threads.find((candidate) => candidate.threadId === threadId);
      if (thread === undefined) return;
      await resolvedWorkbenchAdapters.workspace.open({
        workspaceId: thread.workspaceId,
        target: "file_explorer",
        relativePath: "",
      });
    },
    [conversation.threads, resolvedWorkbenchAdapters.workspace],
  );
  /**
   * Controller 会返回稳定的 action callbacks；先拆出具体函数，避免导航/命令回调把整个
   * Conversation view model 当成依赖，从而让流式 Timeline 更新重建左侧导航入口。
   */
  const conversationCreate = conversation.create;
  const conversationSelect = conversation.select;
  const conversationPin = conversation.pin;
  const conversationArchive = conversation.archive;
  const conversationRestore = conversation.restore;
  const conversationRename = conversation.rename;
  const currentThread = conversation.threads.find(
    (thread) => thread.threadId === conversation.currentThreadId,
  );
  const exportScopeRef = useRef({
    threadId: conversation.currentThreadId,
    workspaceId: currentThread?.workspaceId,
  });
  exportScopeRef.current = {
    threadId: conversation.currentThreadId,
    workspaceId: currentThread?.workspaceId,
  };
  /** 导出按点击时身份分页读取；切换 Thread/Workspace 后中止交付，避免把旧会话保存成当前会话。 */
  const exportCurrentConversation = useCallback(async (): Promise<
    "saved" | "cancelled" | "downloaded"
  > => {
    const threadId = conversation.currentThreadId;
    const thread = conversation.threads.find((candidate) => candidate.threadId === threadId);
    if (threadId === undefined || thread === undefined) throw new Error("当前对话暂不可导出。");
    const isCurrent = (): boolean => {
      const current = exportScopeRef.current;
      return current.threadId === threadId && current.workspaceId === thread.workspaceId;
    };
    const { markdown } = await exportConversationMarkdown(resolvedHistoryAdapter, {
      threadId,
      title: thread.title,
      isCurrent,
    });
    if (!isCurrent()) throw new Error("当前对话已切换，请重新开始导出。");
    const filename = conversationMarkdownFilename(thread.title);
    const outcome = await exportPublicMarkdown(markdown, filename);
    if (outcome === "unsupported") return downloadPublicMarkdown(markdown, filename);
    return outcome;
  }, [conversation.currentThreadId, conversation.threads, resolvedHistoryAdapter]);
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
  const openTargetScopeToken = useMemo(
    () => ({ scope: turnReviewScopeIdentity }),
    [turnReviewScopeIdentity],
  );
  const [openTargetRequestRecord, setOpenTargetRequestRecord] = useState<
    { scopeToken: object; request: ConversationOpenTargetRequest } | undefined
  >();
  const openTargetRequest =
    openTargetRequestRecord?.scopeToken === openTargetScopeToken
      ? openTargetRequestRecord.request
      : undefined;
  /** Scope token 每次切换都会更换，即便稍后回到同一 Thread 也不会重放旧点击请求。 */
  const setOpenTargetRequest = useCallback(
    (request: ConversationOpenTargetRequest | undefined): void => {
      setOpenTargetRequestRecord(
        request === undefined ? undefined : { scopeToken: openTargetScopeToken, request },
      );
    },
    [openTargetScopeToken],
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
  /** commit 撤销旧来源和一次性打开请求；隐藏会话的迟到完成不得清空新请求或抢焦点。 */
  useLayoutEffect(() => {
    activeWorkbenchScopeRef.current = turnReviewScopeIdentity;
    workspaceReferencePreviewSourceRef.current = undefined;
    openTargetSourceRef.current = undefined;
    attachmentPreviewSourceRef.current = null;
  }, [turnReviewScopeIdentity]);
  const planAvailable =
    runtimeState !== undefined &&
    (runtimeState.status === "ready" || runtimeState.status === "busy") &&
    runtimeState.features.includes("plan");
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

  const required = false;
  const pageNavigation = usePageNavigationController(required);
  const { settingsVisible, settingsSection, setSettingsSection, navigate, goBack, goForward } =
    pageNavigation;
  /** 设置页会隐藏整个 Workspace 布局；先把当前 Workbench 焦点移到仍可见的标题栏。 */
  const navigateToSettings = useCallback((): void => {
    moveVisibleWorkbenchFocusToTitlebar();
    navigate("settings");
  }, [navigate]);
  /** 恢复后的存储只提示一次；关闭浮动提示后仍可查看详情。 */
  useEffect(() => {
    const recovery = runtimeState?.storageRecovery ?? runtimeStorage?.storageRecovery;
    if (recovery?.phase === "blocked") {
      toast.error(storageRecoveryMessage(recovery), {
        id: "ja-storage-blocked",
        duration: Infinity,
        action: {
          label: "查看详情",
          onClick: () => {
            setSettingsSection("storage");
            navigateToSettings();
          },
        },
        cancel:
          retryStorage === undefined || !canRetryStorageRecovery(recovery)
            ? undefined
            : {
                label: "重试",
                onClick: () => {
                  toast.loading("正在恢复本地数据…", { id: "ja-storage-blocked" });
                  void retryLocalStorage().catch(() =>
                    toast.error("仍未恢复，请查看数据与存储", { id: "ja-storage-blocked" }),
                  );
                },
              },
      });
      return;
    }
    toast.dismiss("ja-storage-blocked");
    if (recovery?.phase !== "ready" || !recovery.rebuilt) return;
    const key = "ja.storage-recovery-notice";
    try {
      if (localStorage.getItem(key) === recovery.id) return;
      localStorage.setItem(key, recovery.id);
    } catch {
      /* 提示状态的持久化是可选能力，失败不能阻断应用。 */
    }
    toast(storageRecoveryMessage(recovery), {
      id: "ja-storage-recovery",
      duration: Infinity,
      closeButton: true,
      action: {
        label: "查看详情",
        onClick: () => {
          setSettingsSection("storage");
          navigateToSettings();
        },
      },
      cancel:
        recovery.backupAvailable && openStorageBackup !== undefined
          ? {
              label: "打开备份",
              onClick: () => {
                void openStorageBackup(recovery.id).catch(() => toast.error("未能打开备份目录"));
              },
            }
          : undefined,
    });
  }, [
    runtimeState?.storageRecovery,
    runtimeStorage?.storageRecovery,
    navigateToSettings,
    setSettingsSection,
    openStorageBackup,
    retryStorage,
    retryLocalStorage,
  ]);
  const [settingsSelection, setSettingsSelection] = useState<{
    visible: boolean;
    projectId?: string;
  }>({ visible: settingsVisible });
  // 只在设置可见性切换时调整本地筛选状态；下一次进入总是从当时的当前项目开始。
  if (settingsSelection.visible !== settingsVisible) {
    setSettingsSelection({ visible: settingsVisible });
  }
  /** 用户选择只改变这次设置访问的目标，不触碰当前会话的 workspace。 */
  const setSettingsProjectId = useCallback((projectId: string): void => {
    setSettingsSelection({ visible: true, projectId });
  }, []);
  const settingsTargetProjectId =
    (settingsSelection.visible === settingsVisible ? settingsSelection.projectId : undefined) ??
    (settingsVisible ? settingsWorkspaceScope?.workspaceId : undefined);
  const settingsTargetProjectIdRef = useRef(settingsTargetProjectId);
  settingsTargetProjectIdRef.current = settingsTargetProjectId;
  /** 设置编辑面独立读取所选项目；对话继续使用当前工作区的 settings 快照。 */
  const settingsEditor = useSettingsController({
    adapter: settingsAdapter ?? DEFAULT_SETTINGS_ADAPTER,
    appearancePort,
    workspaceScope:
      settingsTargetProjectId === undefined
        ? undefined
        : { workspaceId: settingsTargetProjectId, kind: "project" },
    runtimeState,
    boot,
    configurationChange: projectSettingsConfigurationChange(lastConfigurationEvent),
    runtimePort: settingsRuntimePort,
    active: settingsVisible,
    catalogSection:
      settingsSection === "skills" || settingsSection === "mcp" ? settingsSection : "none",
  });
  const reloadSettingsEditor = settingsEditor.reload;
  const setWorkspaceProjectTrust = workspace.setProjectTrust;
  /** 固定 Settings 所选项目；失败也回读同一配置 scope，保留原 mutation 错误供确认层重试。 */
  const setSettingsProjectTrust = useCallback(
    async (workspaceId: string, trust: "trusted" | "untrusted"): Promise<void> => {
      if (settingsTargetProjectId !== workspaceId)
        throw new Error("settings project selection changed");
      try {
        await setWorkspaceProjectTrust(workspaceId, trust);
      } catch (mutationFailure: unknown) {
        if (settingsTargetProjectIdRef.current === workspaceId) {
          try {
            await reloadSettingsEditor();
          } catch {
            // 保留 mutation 原错误；确认层继续打开，避免误示为成功。
          }
        }
        throw mutationFailure;
      }
      if (settingsTargetProjectIdRef.current === workspaceId) await reloadSettingsEditor();
    },
    [reloadSettingsEditor, setWorkspaceProjectTrust, settingsTargetProjectId],
  );
  const isProjectScope = workspace.workspace?.kind === "project";
  // native general scope 与已选项目都是有效 conversation scope。Navigation action 与
  // Composer 等待相同 lifecycle/history gate，启动期不能在 Thread/runtime admission
  // 成为权威前暴露可点击动作。
  const settingsScopeReady =
    settings.scopeReady &&
    settings.scopeWorkspaceId ===
      (workspace.workspace?.kind === "project" ? workspace.workspace.workspaceId : undefined);
  const conversationScopeReady =
    settingsScopeReady &&
    (workspace.workspace === undefined || workspace.bindingReady) &&
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
  const plan = usePlanController({
    ownerThreadId: conversation.currentThreadId,
    visible: planAvailable,
    detailsVisible: planAvailable && inspectorOpen && workbenchVisible && workbenchTab === "plan",
    port: planPort,
  });
  const composerNativeDrop = useComposerNativeDropRouter(
    nativeDropPort,
    !settingsVisible && conversationScopeReady,
  );

  /** 将 Markdown 目标冻结为当前 Thread 的一次性 intent；Explorer 可在右栏隐藏时处理。 */
  const requestConversationOpenTarget = useCallback(
    (target: ConversationOpenTargetRequest["target"], source?: HTMLElement): void => {
      const workspaceId = workspace.workspace?.workspaceId;
      const threadId = workbenchThreadId;
      if (workspaceId === undefined || threadId === undefined) {
        toast.error("当前会话没有可用的工作区，请先打开工作区后重试。", {
          id: "ja-open-target:workspace-unavailable",
        });
        restoreConversationObjectFocus(source ?? null);
        return;
      }
      if (!workspace.bindingReady && target.kind !== "url" && target.kind !== "explorer") {
        toast.warning("项目目录不可用，请修改目录或重新检查后打开文件。", {
          id: "ja-open-target:directory-unavailable",
          action: {
            label: "修改目录",
            onClick: () => {
              void editProject(workspaceId);
            },
          },
        });
        restoreConversationObjectFocus(source ?? null);
        return;
      }
      const requestId = ++openTargetRequestSequenceRef.current;
      openTargetSourceRef.current = { requestId, workspaceId, threadId, source: source ?? null };
      setOpenTargetRequest({
        requestId,
        workspaceId,
        threadId,
        bindingRevision: `${workspace.workspace?.rootPath ?? ""}:${workspace.workspace?.revision ?? 0}`,
        target,
      });
    },
    [
      setOpenTargetRequest,
      workbenchThreadId,
      workspace.workspace?.workspaceId,
      workspace.workspace?.rootPath,
      workspace.workspace?.revision,
      workspace.bindingReady,
      editProject,
    ],
  );

  /** 点击 HTTP(S) 同样只创建 Ja Preview intent，绝不把模型给出的 href 交给主 WebView。 */
  const openConversationWebLink = useCallback(
    (url: string, source?: HTMLElement): void => {
      requestConversationOpenTarget({ kind: "url", url }, source);
    },
    [requestConversationOpenTarget],
  );

  /** 普通点击进右栏，Ctrl+点击沿用同一 Thread intent 但不改变当前工作面。 */
  const openConversationFileLink = useCallback(
    (
      target: { path: string; line?: number; column?: number },
      source: HTMLElement,
      mode?: "explorer" | "browser",
    ): void => {
      requestConversationOpenTarget(
        mode === "explorer"
          ? { kind: "explorer", path: target.path }
          : mode === "browser"
            ? { kind: "preview", ...target }
            : { kind: "file", ...target },
        source,
      );
    },
    [requestConversationOpenTarget],
  );

  /** 只结算仍属于当前 Thread 的 ACK；失败来源保持可见并归还焦点，迟到后台 ACK 不切换 UI。 */
  const settleConversationOpenTarget = useCallback(
    (requestId: number, outcome: ConversationOpenTargetOutcome): void => {
      if (openTargetRequest?.requestId !== requestId) return;
      setOpenTargetRequest(undefined);
      const source = openTargetSourceRef.current;
      openTargetSourceRef.current = undefined;
      if (
        outcome === "failed" &&
        activeWorkbenchScopeRef.current === turnReviewScopeIdentity &&
        source?.requestId === requestId &&
        source.workspaceId === workspace.workspace?.workspaceId &&
        source.threadId === workbenchThreadId
      )
        restoreConversationObjectFocus(source.source);
    },
    [
      openTargetRequest?.requestId,
      setOpenTargetRequest,
      turnReviewScopeIdentity,
      workbenchThreadId,
      workspace.workspace?.workspaceId,
    ],
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

  /** 只结算当前 Thread 的 request；打开后保留来源，失败或关闭时消费来源并恢复焦点。 */
  const settleWorkspaceReferencePreview = useCallback(
    (requestId: number, outcome: WorkspaceReferencePreviewOutcome): void => {
      if (activeWorkbenchScopeRef.current !== turnReviewScopeIdentity) return;
      if (workspaceReferencePreviewRequest?.requestId !== requestId) return;
      if (outcome === "opened") return;
      if (outcome !== "failed" && outcome !== "closed") return;
      const allowedRestoreFocus = activeWorkbenchPortalTrigger() ?? undefined;
      const expectedFocus =
        moveVisibleWorkbenchFocusToTitlebar() ??
        (document.activeElement === document.body ? document.body : null);
      setWorkspaceReferencePreviewRequest(undefined);
      setInspectorOpen(false);
      const source = workspaceReferencePreviewSourceRef.current;
      workspaceReferencePreviewSourceRef.current = undefined;
      if (expectedFocus !== null)
        restoreConversationObjectFocus(
          source?.requestId === requestId && source.workspaceId === workspace.workspace?.workspaceId
            ? source.source
            : null,
          expectedFocus,
          () => activeWorkbenchScopeRef.current === turnReviewScopeIdentity,
          allowedRestoreFocus,
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
  const interfacePreferences = useInterfacePreferences();
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
    Pick<ConversationSummary, "scope" | "gitBranch" | "runtime">
  >(
    () => ({
      scope: isProjectScope ? (workspace.workspace?.displayName ?? "当前项目") : "无项目对话",
      gitBranch: isProjectScope ? currentGitBranch : undefined,
      runtime: runtimeLabel(boot.status),
    }),
    [boot.status, currentGitBranch, isProjectScope, workspace.workspace?.displayName],
  );

  /**
   * 会话聚焦快捷键不改变抽屉开闭；抽屉已打开时返回 false，让唯一的抽屉按钮继续拥有
   * 布局状态，避免 Review/Files/Terminal/Preview 快捷键与面板按钮互相争用。
   */
  const returnToConversation = useCallback((): boolean => {
    if (!conversationShortcutFocusEnabled || inspectorOpen) return false;
    const composer = currentConversationComposer();
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
    const composer = currentConversationComposer();
    if (composer === null || composer.disabled) return false;
    const currentScope = activeWorkbenchScopeRef.current === turnReviewScopeIdentity;
    if (!currentScope) return false;
    const allowedRestoreFocus = activeWorkbenchPortalTrigger() ?? undefined;
    const expectedFocus =
      moveVisibleWorkbenchFocusToTitlebar() ??
      (document.activeElement === document.body ? document.body : null);
    setInspectorOpen(false);
    if (expectedFocus !== null)
      focusConversationComposerAfterNavigation(
        expectedFocus ?? undefined,
        () => activeWorkbenchScopeRef.current === turnReviewScopeIdentity,
        allowedRestoreFocus,
      );
    return true;
  }, [conversationShortcutFocusEnabled, inspectorOpen, setInspectorOpen, turnReviewScopeIdentity]);

  /** 在 Files flush 前只捕获当前焦点 owner；失败时 token 随关闭调用丢弃，不提前改界面状态。 */
  const captureFilesCapabilityCloseFocus = useCallback(
    (workspaceId: string): FilesCapabilityCloseFocusCapture | null => {
      if (
        activeWorkbenchScopeRef.current !== turnReviewScopeIdentity ||
        workspace.workspace?.workspaceId !== workspaceId
      )
        return null;
      const source = workspaceReferencePreviewSourceRef.current;
      if (source === undefined || source.workspaceId !== workspaceId) return null;

      const activeElement = document.activeElement;
      const allowedRestoreFocus = activeWorkbenchPortalTrigger();
      const workbench = document.querySelector<HTMLElement>("#workbench");
      const focusBelongsToCurrentWorkbench =
        activeElement instanceof HTMLElement &&
        (workbench?.contains(activeElement) === true || allowedRestoreFocus !== null) &&
        activeElement.closest<HTMLElement>("[hidden], [inert]") === null &&
        (allowedRestoreFocus !== null ||
          activeElement.closest<HTMLElement>('[aria-hidden="true"]') === null);
      const expectedFocus =
        activeElement === document.body || focusBelongsToCurrentWorkbench
          ? (activeElement as HTMLElement)
          : null;
      return {
        workspaceId,
        scopeIdentity: turnReviewScopeIdentity,
        requestId: source.requestId,
        expectedFocus,
        allowedRestoreFocus,
      };
    },
    [turnReviewScopeIdentity, workspace.workspace?.workspaceId],
  );

  /** 只消费同一 Workspace、Thread 与来源请求的 flush ACK；失败时不会调用此处或恢复焦点。 */
  const closeFilesCapability = useCallback(
    (workspaceId: string, focusCapture: FilesCapabilityCloseFocusCapture | null): void => {
      if (
        activeWorkbenchScopeRef.current !== turnReviewScopeIdentity ||
        focusCapture === null ||
        focusCapture.workspaceId !== workspaceId ||
        focusCapture.scopeIdentity !== turnReviewScopeIdentity ||
        workspace.workspace?.workspaceId !== workspaceId
      )
        return;
      const source = workspaceReferencePreviewSourceRef.current;
      if (
        source === undefined ||
        source.workspaceId !== workspaceId ||
        source.requestId !== focusCapture.requestId
      )
        return;
      /** 最后一个 Files Tab 的 ACK 随后会进入 changeWorkbenchTabs，由它统一恢复来源或 Composer。 */
      if (workbenchTabs.length === 1 && workbenchTabs[0] === "files") return;
      setWorkspaceReferencePreviewRequest((current) =>
        current?.requestId === source.requestId ? undefined : current,
      );
      workspaceReferencePreviewSourceRef.current = undefined;
      if (focusCapture.expectedFocus !== null)
        restoreConversationObjectFocus(
          source.source,
          focusCapture.expectedFocus,
          () => activeWorkbenchScopeRef.current === turnReviewScopeIdentity,
          focusCapture.allowedRestoreFocus ?? undefined,
        );
    },
    [
      setWorkspaceReferencePreviewRequest,
      turnReviewScopeIdentity,
      workbenchTabs,
      workspace.workspace?.workspaceId,
    ],
  );

  /** scope 门禁先保护共享来源；关闭当前 Files 时恢复引用对象，portal 菜单归焦只认配对触发器。 */
  const closeWorkbench = useCallback((): void => {
    const currentScope = activeWorkbenchScopeRef.current === turnReviewScopeIdentity;
    if (!currentScope) return;
    const allowedRestoreFocus = activeWorkbenchPortalTrigger() ?? undefined;
    const expectedFocus =
      moveVisibleWorkbenchFocusToTitlebar() ??
      (document.activeElement === document.body ? document.body : null);
    setInspectorOpen(false);
    const source = workspaceReferencePreviewSourceRef.current;
    setWorkspaceReferencePreviewRequest(undefined);
    workspaceReferencePreviewSourceRef.current = undefined;
    if (expectedFocus === null) return;
    if (workbenchTab !== "files") {
      focusConversationComposerAfterNavigation(
        expectedFocus,
        () => activeWorkbenchScopeRef.current === turnReviewScopeIdentity,
      );
      return;
    }
    restoreConversationObjectFocus(
      source !== undefined && source.workspaceId === workspace.workspace?.workspaceId
        ? source.source
        : null,
      expectedFocus,
      () => activeWorkbenchScopeRef.current === turnReviewScopeIdentity,
      allowedRestoreFocus,
    );
  }, [
    setInspectorOpen,
    setWorkspaceReferencePreviewRequest,
    turnReviewScopeIdentity,
    workbenchTab,
    workspace.workspace?.workspaceId,
  ]);

  /** 空 Tab 列表会自动收起面板；Files 来源先消费并恢复，无来源的最后 Tab 返回主 Composer。 */
  const changeWorkbenchTabs = useCallback(
    (tabs: typeof workbenchTabs): void => {
      const collapsingVisibleWorkbench = tabs.length === 0 && workbenchVisible;
      const currentScope = activeWorkbenchScopeRef.current === turnReviewScopeIdentity;
      const expectedFocus =
        collapsingVisibleWorkbench && currentScope
          ? (moveVisibleWorkbenchFocusToTitlebar() ??
            (document.activeElement === document.body ? document.body : null))
          : null;
      const source =
        collapsingVisibleWorkbench && currentScope && workbenchTab === "files"
          ? workspaceReferencePreviewSourceRef.current
          : undefined;
      setWorkbenchTabs(tabs);
      if (source !== undefined && source.workspaceId === workspace.workspace?.workspaceId) {
        setWorkspaceReferencePreviewRequest((current) =>
          current?.requestId === source.requestId ? undefined : current,
        );
        workspaceReferencePreviewSourceRef.current = undefined;
        if (expectedFocus !== null)
          restoreConversationObjectFocus(
            source.source,
            expectedFocus,
            () => activeWorkbenchScopeRef.current === turnReviewScopeIdentity,
          );
        return;
      }
      if (expectedFocus !== null)
        focusConversationComposerAfterNavigation(
          expectedFocus,
          () => activeWorkbenchScopeRef.current === turnReviewScopeIdentity,
        );
    },
    [
      setWorkbenchTabs,
      setWorkspaceReferencePreviewRequest,
      turnReviewScopeIdentity,
      workbenchTab,
      workbenchVisible,
      workspace.workspace?.workspaceId,
    ],
  );

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
    await conversationCreate();
    navigate("workspace");
    focusConversationComposerAfterNavigation();
  }, [conversationCreate, navigate]);
  /** 右键新建先按项目身份选中并检查绑定，目录不可用时保留历史入口并给出修复提示。 */
  const createProjectConversation = useCallback(
    async (workspaceId: string): Promise<void> => {
      try {
        await conversationCreate({ workspaceId });
        navigate("workspace");
        focusConversationComposerAfterNavigation();
      } catch {
        toast.error("项目目录不可用，请修改目录或重新检查后新建对话。");
      }
    },
    [conversationCreate, navigate],
  );

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

  /** 切换到无项目会话聚合列表；目录留空以等待用户选择或创建一个具体 Thread。 */
  const selectNoProject = useCallback(async (): Promise<void> => {
    await workspace.selectNoProject();
  }, [workspace]);

  /** 恢复持久 Thread 后再转交输入焦点，避免侧栏或搜索结果继续持有键盘落点。 */
  const selectConversation = useCallback(
    async (threadId: string): Promise<void> => {
      await conversationSelect(threadId);
      navigate("workspace");
      focusConversationComposerAfterNavigation();
    },
    [conversationSelect, navigate],
  );

  /** 行级置顶失败只显示固定产品文案，不把原生异常或路径带入 Toast。 */
  const pinConversation = useCallback(
    async (threadId: string, pinned: boolean): Promise<void> => {
      try {
        await conversationPin(threadId, pinned);
      } catch {
        toast.error(pinned ? "置顶失败，请重试。" : "取消置顶失败，请重试。");
      }
    },
    [conversationPin],
  );

  /** 归档成功后提供 8 秒撤销；恢复始终调用服务端 restore，不复用本地旧行。 */
  const archiveConversation = useCallback(
    async (threadId: string): Promise<void> => {
      try {
        const undo = await conversationArchive(threadId);
        if (undo === undefined) return;
        toast.success("对话已归档", {
          duration: 8_000,
          action: {
            label: "撤销",
            onClick: () => {
              void conversationRestore(undo.archived.threadId, undo.restoreSelection).catch(() =>
                toast.error("恢复失败，请从搜索中重试。"),
              );
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
    [conversationArchive, conversationRestore],
  );

  /** 搜索中的归档结果先恢复再打开，并与普通会话切换共享 Composer 焦点终点。 */
  const restoreConversation = useCallback(
    async (threadId: string): Promise<void> => {
      try {
        await conversationRestore(threadId, true);
        navigate("workspace");
        focusConversationComposerAfterNavigation();
      } catch {
        toast.error("恢复失败，请重试。");
      }
    },
    [conversationRestore, navigate],
  );
  /** 对话搜索开关保持稳定 identity，避免 Timeline 更新穿透 memoized NavigationSidebar。 */
  const openConversationSearch = useCallback((): void => {
    setConversationSearchOpen(true);
  }, []);
  /** 设置导航保持稳定 identity；实际历史栈仍由 page navigation controller 唯一持有。 */
  const openSettings = useCallback((): void => {
    navigateToSettings();
  }, [navigateToSettings]);
  /** 标题栏管理入口跳转到设置中的 MCP 分类，不切换当前会话。 */
  const openMcpSettings = useCallback((): void => {
    setSettingsSection("mcp");
    navigateToSettings();
  }, [navigateToSettings, setSettingsSection]);
  /** 无可用模型或连接时直接进入模型配置，保留当前 Thread 与 Composer 草稿。 */
  const openModelSettings = useCallback((): void => {
    setSettingsSection("models");
    navigateToSettings();
  }, [navigateToSettings, setSettingsSection]);
  /** 设置层关闭后复用统一会话焦点终点，避免键盘用户返回到已卸载的设置按钮。 */
  const returnFromSettings = useCallback((): void => {
    pendingSettingsReturnFocusRef.current = true;
    navigate("workspace");
  }, [navigate]);

  /**
   * DOM 与 native 快捷键共用的唯一动作入口。返回 true 只表示当前状态确实接纳
   * 了动作；历史导航先迁出 Workbench 焦点，避免目标页面隐藏持焦点的布局。
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
          navigateToSettings();
          return true;
        case "go-back":
          if (!pageNavigation.canGoBack) return false;
          moveVisibleWorkbenchFocusToTitlebar();
          goBack();
          return true;
        case "go-forward":
          if (!pageNavigation.canGoForward) return false;
          moveVisibleWorkbenchFocusToTitlebar();
          goForward();
          return true;
      }
    },
    [
      conversationScopeReady,
      createConversation,
      goBack,
      goForward,
      navigateToSettings,
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
              invoke: () => navigateToSettings(),
            },
          ]),
    ],
    [
      chooseProject,
      conversationScopeReady,
      createConversation,
      isProjectScope,
      navigate,
      navigateToSettings,
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
        id: "btw",
        name: "btw",
        aliases: ["侧聊"],
        label: "新建侧聊",
        description: "从当前对话打开一个临时侧聊",
        available: !required && conversationScopeReady && sideChatLauncherReady,
        unavailableReason: required
          ? "完成必要设置后可新建侧聊"
          : !conversationScopeReady
            ? "当前会话范围尚未就绪"
            : "侧聊正在准备",
        argument: { mode: "optional", label: "内容", placeholder: "描述要在侧聊处理的事情" },
        /** 空参数只打开并聚焦侧聊；有参数由同一 launcher 创建后显式发送首条 follow-up。 */
        execute: ({ argument }) => requestSideChat(argument),
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
        execute: () => navigateToSettings(),
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
      navigateToSettings,
      openLatestTurnReview,
      pageNavigation.canGoBack,
      pageNavigation.canGoForward,
      platform,
      required,
      requestSideChat,
      settingsVisible,
      sideChatLauncherReady,
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

  const dataStorage: DataStorageProps = {
    info: runtimeStorage,
    recovery: runtimeState?.storageRecovery,
    error: storageReadError,
    onReload: reloadStorage,
    onOpenBackup: openStorageBackup,
    onRetry: retryStorage === undefined ? undefined : retryLocalStorage,
  };
  const settingsMainView =
    runtimeState?.storageRecovery?.phase === "blocked" ||
    (settingsSection === "storage" && (settings.loading || settings.error !== undefined)) ? (
      <section className="ja-settings-view" aria-label="设置页面">
        <div className="ja-settings">
          <div className="ja-settings-tabs">
            <aside className="ja-settings-sidebar">
              <Button className="ja-settings-return" variant="ghost" onClick={returnFromSettings}>
                <ArrowLeft aria-hidden="true" />
                返回应用
              </Button>
              <div className="ja-settings-nav" role="tablist" aria-label="设置分类">
                <button
                  type="button"
                  className="ja-settings-nav-item"
                  role="tab"
                  id="ja-storage-recovery-tab"
                  aria-controls="ja-storage-recovery-panel"
                  aria-selected="true"
                  data-state="active"
                >
                  <Database aria-hidden="true" />
                  <span>数据与存储</span>
                </button>
              </div>
            </aside>
            <div className="ja-settings-main">
              <header className="ja-settings-toolbar">
                <strong>设置</strong>
              </header>
              <div className="ja-settings-content">
                <div
                  className="ja-settings-panel"
                  role="tabpanel"
                  id="ja-storage-recovery-panel"
                  aria-labelledby="ja-storage-recovery-tab"
                >
                  <DataStorageSection {...dataStorage} />
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>
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
      <SettingsView
        dataStorage={dataStorage}
        settings={settings}
        projectSettings={settingsEditor}
        projects={workspace.projects}
        selectedProjectId={settingsTargetProjectId}
        onSelectProject={setSettingsProjectId}
        onSetProjectTrust={setSettingsProjectTrust}
        interfacePreferences={interfacePreferences}
        executionScope={{
          scopedDefault: settings.snapshot.defaultAccessMode,
          projectOverride: settings.loaded?.projectOverrides.accessMode ?? false,
          scopeReady: settingsScopeReady,
          workspaceKind: workspace.workspace?.kind === "project" ? "project" : "general",
          threadAccessMode:
            currentThread?.workspaceId === workspace.workspace?.workspaceId
              ? currentThread?.preferences?.accessMode
              : undefined,
        }}
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
      active={!settingsVisible && !(singlePaneWorkbench && workbenchVisible)}
      projectEditorOpen={editingProject !== undefined}
      interactionPort={DEFAULT_INTERACTION_PORT}
      workspace={workspace}
      conversation={conversation}
      usageReader={conversationUsageReader}
      mcpReader={conversationMcpReader}
      onOpenMcpSettings={openMcpSettings}
      onOpenModelSettings={openModelSettings}
      settings={settings}
      inspectorOpen={inspectorOpen}
      onToggleInspector={toggleInspector}
      summaryContext={conversationSummaryContext}
      workspaceAdapter={resolvedWorkbenchAdapters.workspace}
      onOpenLink={openConversationWebLink}
      onOpenFile={openConversationFileLink}
      onEditProject={editProject}
      onCopyText={desktopAdapters.writeText}
      onConversationFocusAvailabilityChange={setConversationFocusAvailable}
      onRegisterWorkspaceReferenceTarget={registerWorkspaceReferenceTarget}
      onOpenWorkspaceReference={openWorkspaceReferencePreview}
      attachmentPort={attachmentPort}
      attachmentPreviewPort={attachmentPreviewPort}
      artifactPort={conversationArtifactPort}
      onExportConversation={exportCurrentConversation}
      processSession={processSessionBinding}
      nativeDropEvent={composerNativeDrop.event}
      onRegisterComposerDropZone={composerNativeDrop.registerDropZone}
      onOpenAttachmentPreview={openAttachmentPreview}
      onLatestTurnReviewChange={publishLatestTurnReview}
      onOpenTurnReview={openTurnReview}
      onAttachmentRemoved={closeRemovedAttachmentPreview}
      onAttachmentsBound={bindAttachmentPreview}
      slashCommands={composerSlashCommands}
      onOpenTask={openTaskFromTimeline}
      planAvailable={planAvailable}
      plan={plan}
      onOpenPlan={() => showWorkbenchCapability("plan")}
    />
  );
  const inspectorContent =
    workbenchHostWorkspace === undefined ? null : (
      <ThreadWorkbenchSessions
        scopeKey={workbenchScopeIdentity}
        workspace={workbenchHostWorkspace}
        generation={runtimeState?.generation}
        adapters={resolvedWorkbenchAdapters}
        active={workbenchVisible && workbenchThreadId !== undefined && workspace.bindingReady}
        rootThreadId={workbenchThreadId}
        parentThreadRevision={currentParentThreadRevision}
        onRegisterSideChatLauncher={registerSideChatLauncher}
        taskPort={taskAdapter}
        taskTranscriptPort={taskTranscriptPort}
        taskMessageContentRead={readTaskMessageContent}
        taskAnswerContentRead={readTaskAnswerContent}
        taskThreadRenamePort={taskThreadRenamePort}
        taskNativeDropPort={nativeDropPort}
        taskPreferencesPort={taskPreferencesPort}
        taskPlanPort={planPort}
        taskInteractionPort={DEFAULT_INTERACTION_PORT}
        taskModels={taskModels}
        taskParentPreferences={
          currentThread?.preferences === null ? undefined : currentThread?.preferences
        }
        taskDefaultPreferences={
          settings.snapshot.defaultSelection === null
            ? undefined
            : {
                selection: settings.snapshot.defaultSelection satisfies ConversationModelSelection,
                accessMode: settings.snapshot.defaultAccessMode,
              }
        }
        taskAttachmentPort={attachmentPort}
        taskArtifactPort={conversationArtifactPort}
        taskUsageReader={conversationUsageReader}
        taskComposerSkills={taskComposerSkills}
        selectedTab={workbenchTab}
        onTabChange={changeWorkbenchTab}
        onShowCapability={showWorkbenchCapability}
        openTabs={workbenchTabs}
        onOpenTabsChange={changeWorkbenchTabs}
        capabilityShortcuts={workbenchCapabilityShortcuts}
        onCopyText={desktopAdapters.writeText}
        onOpenExternalUrl={desktopAdapters.openExternalUrl}
        onClose={closeWorkbench}
        onRegisterFilesLifecycle={filesCapability.register}
        onRegisterRebindLifecycle={registerRebindLifecycle}
        onRegisterTerminalLifecycle={workspaceChange.registerTerminal}
        onRegisterPreviewLifecycle={workspaceChange.registerPreview}
        onCloseFilesCapability={captureFilesCapabilityCloseFocus}
        onFilesCapabilityClosed={closeFilesCapability}
        onAddWorkspaceReference={addWorkspaceReference}
        openTargetRequest={openTargetRequest}
        onOpenTargetSettled={settleConversationOpenTarget}
        onOpenWorkspaceReference={openWorkspaceReferencePreview}
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
        planAvailable={planAvailable}
        plan={plan}
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
        onReconnect={reconnectAvailable || reconnectPending ? reconnectRuntime : undefined}
        reconnectPending={
          reconnectPending || boot.status === "connecting" || boot.status === "busy"
        }
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
              noProjectSelected={workspace.workspace?.kind !== "project"}
              projectSectionCollapsed={projectSectionCollapsed}
              historySectionCollapsed={historySectionCollapsed}
              runtimeTone={runtimeTone(boot.status)}
              currentThreadId={conversation.currentThreadId}
              threads={conversation.threads}
              historyBusy={
                // 项目切换先由 workspace controller 忙碌，随后由 conversation controller 接续历史恢复；
                // 两者都只投影到“最近对话”标题行，避免旧列表被临时占位替换或因空列表闪现。
                workspace.busy || conversation.busy
              }
              // 历史错误正文与重试统一在 Conversation 通知中；侧栏只隐藏错误时的空历史文案。
              historyHasError={conversation.error?.source === "history-load"}
              newConversationDisabled={!conversationScopeReady}
              projectBusy={
                workspace.busy ||
                settings.synchronizing ||
                (boot.status !== "ready" && boot.status !== "busy")
              }
              compact={compactNavigation}
              platform={platform}
              activeAction={settingsVisible ? "settings" : "workspace"}
              conversationSearchOpen={conversationSearchOpen}
              onNewConversation={createConversation}
              onSelectConversation={selectConversation}
              onOpenProjectFolder={openProjectFolder}
              onNewConversationForProject={createProjectConversation}
              onEditProject={editProject}
              onCopyProjectPath={copyProjectPath}
              onOpenWorkspaceFolder={openThreadWorkspaceFolder}
              onOpenConversationSearch={openConversationSearch}
              onRenameConversation={conversationRename}
              onPinConversation={pinConversation}
              onArchiveConversation={archiveConversation}
              mutatingThreadIds={conversation.mutatingThreadIds}
              onChooseProject={chooseProject}
              onSelectNoProject={selectNoProject}
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
            <main className="ja-main ja-settings-layer">
              {boot.status === "recovery_required" ? (
                <section className="ja-settings-view" aria-label="设置页面">
                  <RecoveryPanel />
                </section>
              ) : (
                settingsMainView
              )}
            </main>
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
      <ProjectEditDialog
        project={editingProject}
        busy={workspace.busy}
        onClose={closeProjectEditor}
        onRestoreFocus={restoreProjectEditorFocus}
        onSave={saveProject}
        onRecheck={recheckEditedProject}
        onChooseDirectory={workspace.pickDirectory}
      />
      <ProjectRebindDialog confirmation={rebindConfirmation} onChoose={chooseRebindDisposition} />
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
