// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactElement,
} from "react";
import { toast } from "sonner";
import { PreviewAdapterError } from "@/api/tauri/preview";
import {
  Workbench,
  capabilityWorkbenchTab,
  parseTaskWorkbenchTabKey,
  taskWorkbenchTab,
  type WorkbenchCapability,
  type WorkbenchTab,
  type WorkbenchTabKey,
  type WorkbenchTaskTab,
} from "@/features/workbench";
import {
  SubagentOverview,
  TaskDetailPanel,
  useTaskController,
  subscribeTaskHostEvents,
  type TaskApprovalPort,
  type TaskComposerEnvironment,
  type TaskComposerSkillSuggestion,
  type TaskPort,
  type TaskResumePort,
  type TaskSummary,
  type TaskThreadRenamePort,
  type TaskTranscriptActions,
  type TaskTranscriptPort,
  type TaskTranscriptSnapshot,
  type TaskPreferencesPort,
  type TaskContentBlock,
} from "@/features/tasks";
import {
  useConversationInteractionController,
  useInteractionController,
  useTimelineStore,
  type ConversationArtifactPort,
  type ConversationAttachmentPort,
  type ConversationAccessMode,
  type ConversationModelOption,
  type ConversationModelSelection,
  type ConversationPreferencesPort,
  type ConversationPlanCreationPort,
  type ConversationThreadPreferences,
  type ConversationTurnPort,
  type ConversationUsageReader,
  type InteractionPort,
  type TimelineRuntimeFence,
} from "@/features/conversation";
import {
  FilesWorkspace,
  useFilesController,
  type FilesWorkspaceLifecycle,
  type WorkspaceFileNode,
} from "@/features/workbench/files";
import {
  MediaPreviewSessionHintStorage,
  PreviewPanelView,
  usePreviewController,
  type AttachmentPreviewPort,
  type AttachmentPreviewTarget,
  type PreviewPort,
} from "@/features/workbench/preview";
import {
  ReviewPanelView,
  TurnReviewPanelView,
  useAiReviewController,
  useReviewController,
  type AiReviewAccepted,
  type ReviewNavigationState,
  type ReviewSource,
  type TurnReviewPort,
  type TurnReviewTarget,
} from "@/features/workbench/review";
import {
  LocalTerminalLayoutStorage,
  useTerminalLayoutPersistence,
} from "@/features/workbench/terminal";
import { LoadingState } from "@/shared/ui/primitives";
import type { WorkspaceProjection } from "@/features/workspace";
import { createNotifyingFilesOperations } from "../application/createNotifyingFilesOperations";
import { usePreviewWorkspaceLifecycle } from "../application/usePreviewWorkspaceLifecycle";
import { useTerminalWorkspaceLifecycle } from "../application/useTerminalWorkspaceLifecycle";
import type { TerminalWorkspaceLifecycle } from "../application/workbenchLifecyclePorts";
import {
  useJaWorkbench,
  type JaWorkbenchAdapters,
  type PreviewWorkspaceLifecycle,
} from "../useJaWorkbench";
import { filesBrowserControllerPorts } from "./filesBrowserControllerPorts";
import { useRuntimeLifecycle, useRuntimeState, useRuntimeTurns } from "../RuntimeProvider";
import {
  PlanWorkbench,
  usePlanController,
  type PlanController,
  type PlanPort,
} from "@/features/plans";
import type { ComposerSlashCommand } from "@/features/conversation";
import type {
  ComposerWorkspaceReferenceTarget,
  WorkspaceReferencePreviewOutcome,
  WorkspaceReferencePreviewRequest,
  ConversationOpenTargetOutcome,
  ConversationOpenTargetRequest,
} from "./ConversationWorkspace";
import type { MarkdownFileTarget } from "@/shared/ui/markdown/SharedMarkdown";
import { ReviewSourceNavigation } from "./ReviewSourceNavigation";
import { threadWorkbenchStorage } from "./threadWorkbenchStorage";
import { conversationModeCommands } from "../application/conversationModeCommands";
import {
  useComposerNativeDropRouter,
  type NativeDropSubscriptionPort,
} from "../application/useComposerNativeDropRouter";

/** Terminal controller 与 xterm renderer 只在能力首次激活后加载。 */
const LazyTerminalWorkbenchSlot = lazy(async () => {
  const terminal = await import("./TerminalWorkbenchSlot");
  return { default: terminal.TerminalWorkbenchSlot };
});

const MAX_REVIEW_NAVIGATION_SCOPES = 16;
const MAX_CHILD_SNAPSHOT_PAGES = 128;
const OPEN_TARGET_TOAST_ID = "ja-open-target";

/** @author kongweiguang：读取失败或被截断时保留 Explorer 恢复动作，不扩大原生文件读取预算。 */
function explorerRecoveryAction(reveal: () => Promise<void>): {
  label: string;
  onClick: () => void;
} {
  return {
    label: "在文件资源管理器中打开",
    onClick: () => {
      void Promise.resolve()
        .then(reveal)
        .catch(() => toast.error("无法在文件资源管理器中显示该路径。"));
    },
  };
}

/** 文件工作区 reader 的失败分类只解释原生返回的有界结果，不借用 Preview 的 1 MiB 投影猜测大小。@author kongweiguang */
function workspaceReadRecoveryReason(
  kind: "text" | "binary" | "unknown_encoding" | "too_large",
  content: string | null,
): string | undefined {
  if (kind === "too_large") return "文件超过 Ja 文件编辑器的读取上限，无法显示正文。";
  if (kind === "unknown_encoding") return "Ja 无法读取此文本文件正文，文件编码不受支持。";
  if (kind === "binary") return "此文件是二进制内容，无法在 Ja 文件编辑器中显示。";
  return content === null ? "Ja 无法读取此文本文件正文，可能是文件权限或编码不兼容。" : undefined;
}

interface SideChatSource {
  readonly threadId: string;
  readonly revision?: number;
  readonly preferences?: ConversationThreadPreferences;
}

/** 能力未启用时只作为 hook 的不可见占位；任何误用都失败关闭。 */
const UNAVAILABLE_PLAN_PORT = new Proxy({} as PlanPort, {
  get: (_target, property: string) =>
    property === "subscribe"
      ? () => () => undefined
      : async () => {
          throw new Error("plan capability unavailable");
        },
});

/** UI hint 的 Git key 包含 Thread、source 与 layer，避免同 Workspace 的会话互相继承浏览状态。 */
function gitReviewNavigationKey(
  workspaceId: string,
  threadId: string | undefined,
  source: ReviewSource,
  layerFilter: string,
): string {
  const sourceIdentity =
    source.kind === "branch"
      ? `branch:${source.refId}`
      : source.kind === "commit"
        ? `commit:${source.commitId}`
        : source.kind;
  return `${workspaceId}:${threadId ?? "unavailable"}:git:${sourceIdentity}:${layerFilter}`;
}

/** Composer 引用只接受 Rust Workspace 协议同形的相对路径，前端预检不替代 native containment。 */
function isSafeWorkspaceReferencePath(path: string): boolean {
  if (
    path.length === 0 ||
    path.length > 4_096 ||
    path.startsWith("/") ||
    path.includes("\\") ||
    path.includes(":")
  )
    return false;
  if (
    [...path].some((character) => {
      const codePoint = character.codePointAt(0) ?? 0;
      return codePoint <= 31 || codePoint === 127;
    })
  )
    return false;
  return path
    .split("/")
    .every((component) => component.length > 0 && component !== "." && component !== "..");
}

/** 只为现有 Files controller 补齐选择动作需要的视图字段，路径与 kind 保持引用原值。 */
function workspaceReferenceNode(request: WorkspaceReferencePreviewRequest): WorkspaceFileNode {
  const { relativePath, kind } = request.reference;
  return {
    id: `conversation-reference:${request.requestId}`,
    name: relativePath.split("/").at(-1) ?? relativePath,
    path: relativePath,
    kind,
  };
}

/**
 * @author kongweiguang
 * 只在 request 所属 Thread Host 内定位可见目标，避免保留会话中同路径文件先命中隐藏节点。
 */
function focusWorkspaceReferenceInFiles(
  request: WorkspaceReferencePreviewRequest,
  rootThreadId: string | undefined,
): void {
  if (rootThreadId === undefined) return;
  const { relativePath, kind } = request.reference;
  const scopeKey = `${request.reference.workspaceId}:${rootThreadId}`;
  const hostRoot = [
    ...document.querySelectorAll<HTMLElement>(
      ".ja-thread-workbench-session[data-thread-workbench-scope]",
    ),
  ].find((candidate) => candidate.dataset["threadWorkbenchScope"] === scopeKey);
  if (
    hostRoot === undefined ||
    hostRoot.closest<HTMLElement>('[hidden], [aria-hidden="true"], [inert]') !== null
  )
    return;
  if (kind === "file") {
    const tab = [...hostRoot.querySelectorAll<HTMLElement>("[data-file-tab-path]")].find(
      (candidate) =>
        candidate.dataset["fileTabPath"] === relativePath &&
        candidate.closest<HTMLElement>('[hidden], [aria-hidden="true"], [inert]') === null,
    );
    const tabButton = tab?.querySelector<HTMLButtonElement>('[role="tab"]');
    if (
      tabButton !== undefined &&
      tabButton !== null &&
      tabButton.closest<HTMLElement>('[hidden], [aria-hidden="true"], [inert]') === null
    )
      tabButton.focus();
    return;
  }
  const row = [...hostRoot.querySelectorAll<HTMLElement>(".ja-file-tree-host [data-path]")].find(
    (candidate) =>
      candidate.dataset["path"] === relativePath &&
      candidate.closest<HTMLElement>('[hidden], [aria-hidden="true"], [inert]') === null,
  );
  const treeItem = row?.closest<HTMLElement>('[role="treeitem"]');
  if (treeItem?.getAttribute("aria-expanded") === "false")
    row?.querySelector<HTMLButtonElement>(".ja-file-tree-disclosure")?.click();
  const focusTarget =
    treeItem ?? hostRoot.querySelector<HTMLElement>('.ja-file-tree-host [role="tree"]');
  if (
    focusTarget !== null &&
    focusTarget.closest<HTMLElement>('[hidden], [aria-hidden="true"], [inert]') === null
  )
    focusTarget.focus();
}

export interface WorkbenchHostProps {
  readonly workspace: WorkspaceProjection;
  readonly generation: number | undefined;
  readonly adapters: JaWorkbenchAdapters;
  readonly active: boolean;
  readonly rootThreadId?: string;
  readonly parentThreadRevision?: number;
  readonly taskPort: TaskPort;
  readonly taskTranscriptPort: TaskTranscriptPort;
  readonly taskMessageContentRead?: (threadId: string, messageId: string) => Promise<string>;
  readonly taskAnswerContentRead?: (
    threadId: string,
    finalMessageId: string,
    minimumRevision?: number,
    turnId?: string,
  ) => Promise<string>;
  readonly taskThreadRenamePort: TaskThreadRenamePort;
  readonly taskAttachmentPort?: ConversationAttachmentPort;
  readonly taskArtifactPort?: ConversationArtifactPort;
  /** 侧聊沿用主会话的窄账本读取端口，但 Thread identity 只由子会话 Composer 提供。 */
  readonly taskUsageReader?: ConversationUsageReader;
  readonly taskComposerSkills?: readonly TaskComposerSkillSuggestion[];
  /** 共享 child Composer 的真实模型目录与父 Thread 一次性默认值。 */
  readonly taskModels?: readonly ConversationModelOption[];
  readonly taskParentPreferences?: ConversationThreadPreferences;
  /** 恢复默认必须读取设置 owner 的全局默认值，不能把当前父 Thread 偏好当作默认值。 */
  readonly taskDefaultPreferences?: {
    selection: ConversationModelSelection;
    accessMode: ConversationAccessMode;
  };
  readonly taskPreferencesPort?: TaskPreferencesPort;
  readonly taskPlanPort?: PlanPort;
  readonly taskNativeDropPort?: NativeDropSubscriptionPort;
  readonly taskInteractionPort?: InteractionPort;
  readonly selectedTab: WorkbenchTabKey;
  readonly onTabChange: (tab: WorkbenchTabKey) => void;
  readonly openTabs: readonly WorkbenchTabKey[];
  readonly onOpenTabsChange: (tabs: readonly WorkbenchTabKey[]) => void;
  readonly capabilityShortcuts: Partial<Record<WorkbenchCapability, string>>;
  readonly onCopyText: (text: string) => Promise<void>;
  readonly onOpenExternalUrl: (url: string) => Promise<void>;
  /** 路径经 native resolver 分类后才展示对应工作面；hidden Workbench 不构成解析阻碍。 */
  readonly onShowCapability?: (capability: "files" | "preview") => void;
  readonly onClose: () => void;
  /** 当前 Thread 的侧聊创建入口；隐藏 Workbench 仍可接收 slash 命令，但非当前会话不注册。 */
  readonly onRegisterSideChatLauncher?: (
    launcher: ((content?: string) => Promise<void>) | undefined,
  ) => void;
  readonly onRegisterFilesLifecycle: (lifecycle: FilesWorkspaceLifecycle | undefined) => void;
  readonly onRegisterTerminalLifecycle: (lifecycle: TerminalWorkspaceLifecycle | undefined) => void;
  readonly onRegisterPreviewLifecycle: (lifecycle: PreviewWorkspaceLifecycle | undefined) => void;
  readonly onCloseFilesCapability: (workspaceId: string) => Promise<void>;
  readonly onAddWorkspaceReference: (reference: ComposerWorkspaceReferenceTarget) => void;
  readonly onOpenWorkspaceReference?: (
    reference: ComposerWorkspaceReferenceTarget,
    source: HTMLButtonElement,
  ) => void;
  readonly workspaceReferencePreviewRequest?: WorkspaceReferencePreviewRequest;
  readonly onWorkspaceReferencePreviewSettled: (
    requestId: number,
    outcome: WorkspaceReferencePreviewOutcome,
  ) => void;
  /** 当前 Thread 的显式消息目标请求；后台 session 必须传 undefined。 */
  readonly openTargetRequest?: ConversationOpenTargetRequest;
  readonly onOpenTargetSettled?: (
    requestId: number,
    outcome: ConversationOpenTargetOutcome,
  ) => void;
  readonly onGitBranchChange: (workspaceId: string, branch: string | undefined) => void;
  readonly attachmentTarget?: AttachmentPreviewTarget;
  readonly attachmentPreviewPort?: AttachmentPreviewPort;
  readonly onOpenAttachmentPreview?: (
    target: AttachmentPreviewTarget,
    source: HTMLButtonElement,
  ) => void;
  readonly onDismissAttachment?: (target: AttachmentPreviewTarget) => void;
  readonly onAttachmentRemoved?: (attachmentId: string) => void;
  readonly onAttachmentsBound?: (threadId: string, attachmentIds: readonly string[]) => void;
  readonly turnReviewTarget?: TurnReviewTarget;
  readonly retainedTurnReviewTarget?: TurnReviewTarget;
  readonly turnReviewScopeLabel?: string;
  readonly requestedTurnReviewPath?: string;
  readonly requestedTurnReviewPathRevision?: number;
  readonly latestTurnReviewAvailable: boolean;
  readonly turnReviewPort: TurnReviewPort;
  readonly onShowRetainedTurnReview: () => void;
  readonly onShowLatestTurnReview: () => void;
  readonly onDismissTurnReview: () => void;
  readonly planAvailable?: boolean;
  readonly plan?: PlanController;
}

/**
 * 保留各 Thread 的编辑器和原生资源；重型 I/O 仅由当前可见且选中的 Tab 激活。
 * 异步结果按最新 Host/Thread owner 收口，不能用旧 render 闭包提交焦点或恢复状态。
 */
export function WorkbenchHost({
  workspace,
  generation,
  adapters,
  active,
  rootThreadId,
  parentThreadRevision,
  taskPort,
  taskTranscriptPort,
  taskMessageContentRead,
  taskAnswerContentRead,
  taskThreadRenamePort,
  taskAttachmentPort,
  taskArtifactPort,
  taskUsageReader,
  taskComposerSkills,
  taskModels = [],
  taskParentPreferences,
  taskDefaultPreferences,
  taskPreferencesPort,
  taskPlanPort,
  taskNativeDropPort,
  taskInteractionPort,
  selectedTab,
  onTabChange,
  openTabs,
  onOpenTabsChange,
  capabilityShortcuts,
  onCopyText,
  onOpenExternalUrl,
  onShowCapability,
  onClose,
  onRegisterSideChatLauncher,
  onRegisterFilesLifecycle,
  onRegisterTerminalLifecycle,
  onRegisterPreviewLifecycle,
  onCloseFilesCapability,
  onAddWorkspaceReference,
  onOpenWorkspaceReference,
  workspaceReferencePreviewRequest,
  onWorkspaceReferencePreviewSettled,
  openTargetRequest,
  onOpenTargetSettled,
  onGitBranchChange,
  attachmentTarget,
  attachmentPreviewPort,
  onOpenAttachmentPreview,
  onDismissAttachment,
  onAttachmentRemoved,
  onAttachmentsBound,
  turnReviewTarget,
  retainedTurnReviewTarget,
  turnReviewScopeLabel,
  requestedTurnReviewPath,
  requestedTurnReviewPathRevision,
  latestTurnReviewAvailable,
  turnReviewPort,
  onShowRetainedTurnReview,
  onShowLatestTurnReview,
  onDismissTurnReview,
  planAvailable = false,
  plan,
}: WorkbenchHostProps): ReactElement {
  // 持久化只隔离 UI 布局和 opaque hint，所有 native 调用仍使用真实 Workspace identity。
  const storageScope = JSON.stringify([workspace.workspaceId, rootThreadId ?? null]);
  const terminalLayoutStorage = useMemo(
    () =>
      new LocalTerminalLayoutStorage(() =>
        threadWorkbenchStorage(globalThis.localStorage, storageScope),
      ),
    [storageScope],
  );
  const previewSessionHintStorage = useMemo(
    () =>
      new MediaPreviewSessionHintStorage(() =>
        threadWorkbenchStorage(globalThis.sessionStorage, storageScope),
      ),
    [storageScope],
  );
  const [tabContextMenuOpen, setTabContextMenuOpen] = useState(false);
  const [reviewNavigationHints, setReviewNavigationHints] = useState<
    Readonly<Record<string, ReviewNavigationState>>
  >({});
  const projection = useJaWorkbench(workspace, adapters, previewSessionHintStorage);
  const runtimeTurns = useRuntimeTurns();
  const { boot, turnAdmissionReady } = useRuntimeState();
  const { queryRuntime } = useRuntimeLifecycle();
  const runtimeGeneration = useTimelineStore((state) => state.handshake.generation);
  const runtimeServerInstanceId = useTimelineStore((state) => state.serverInstanceId);
  const runtimeReady = useTimelineStore((state) => state.handshake.phase === "ready");
  const runtimeFence =
    runtimeReady && runtimeServerInstanceId !== undefined && runtimeGeneration > 0
      ? { serverInstanceId: runtimeServerInstanceId, generation: runtimeGeneration }
      : undefined;
  const rootThreadRevision = useTimelineStore((state) =>
    rootThreadId === undefined
      ? undefined
      : (state.threadRevisionByThread[rootThreadId] ?? state.threads[rootThreadId]?.revision),
  );
  const workspaceReferencePreviewRequestRef = useRef(workspaceReferencePreviewRequest);
  const handledWorkspaceReferenceRequestRef = useRef<number | undefined>(undefined);
  const settledWorkspaceReferenceRequestRef = useRef<
    | {
        requestId: number;
        outcome: WorkspaceReferencePreviewOutcome;
        focusFrameId: number | undefined;
        focusCompleted: boolean;
        expectedFocus: HTMLElement | null;
      }
    | undefined
  >(undefined);
  const openTargetRequestRef = useRef(openTargetRequest);
  const openTargetIntentSequenceRef = useRef(0);
  const openTargetHostScopeRef = useRef({
    active,
    workspaceId: workspace.workspaceId,
    threadId: rootThreadId,
    bindingRevision: `${workspace.rootPath}:${workspace.revision ?? 0}`,
  });
  const processingOpenTargetRequestRef = useRef<number | undefined>(undefined);
  const settledOpenTargetRequestRef = useRef<number | undefined>(undefined);
  const parsedSelectedTask = parseTaskWorkbenchTabKey(selectedTab);
  const activeTaskThreadId = parsedSelectedTask?.taskThreadId;
  /** 仅将所选 Thread 与请求起点 Runtime 身份一致的侧聊历史写入共享 Store，避免迟到读取污染其它会话。@author kongweiguang */
  const applyTaskTranscriptSnapshot = useCallback(
    (snapshot: TaskTranscriptSnapshot): boolean => {
      const timeline = useTimelineStore.getState();
      if (
        !active ||
        activeTaskThreadId === undefined ||
        snapshot.threadId !== activeTaskThreadId ||
        !runtimeReady ||
        runtimeGeneration <= 0 ||
        runtimeServerInstanceId === undefined ||
        timeline.handshake.phase !== "ready" ||
        timeline.handshake.generation !== runtimeGeneration ||
        timeline.serverInstanceId !== runtimeServerInstanceId
      )
        return false;
      const priorSnapshotRevision = timeline.snapshotRevisionByThread[snapshot.threadId];
      const outcome = timeline.applySnapshot(snapshot, workspace.workspaceId);
      return (
        outcome === "applied" ||
        (outcome === "late" &&
          priorSnapshotRevision !== undefined &&
          priorSnapshotRevision >= snapshot.revision)
      );
    },
    [
      active,
      activeTaskThreadId,
      runtimeGeneration,
      runtimeReady,
      runtimeServerInstanceId,
      workspace.workspaceId,
    ],
  );
  /** 通知回调和异步 Files 读取只读取最新请求，旧请求失败不能关闭较新的预览。 */
  useEffect(() => {
    workspaceReferencePreviewRequestRef.current = workspaceReferencePreviewRequest;
  }, [workspaceReferencePreviewRequest]);
  /** Host identity 在 layout commit 同步更新，避免旧 await 在 effect 间隙错误 ACK 新会话。 */
  useLayoutEffect(() => {
    const bindingRevision = `${workspace.rootPath}:${workspace.revision ?? 0}`;
    openTargetRequestRef.current = openTargetRequest;
    openTargetHostScopeRef.current = {
      active,
      workspaceId: workspace.workspaceId,
      threadId: rootThreadId,
      bindingRevision,
    };
  }, [
    active,
    openTargetRequest,
    rootThreadId,
    workspace.rootPath,
    workspace.revision,
    workspace.workspaceId,
  ]);
  /**
   * @author kongweiguang
   * 本地 ready 事实可能先于 RAF；opened 通知只在焦点提交后发一次，且 opened 仍可转为 closed。
   */
  const settleWorkspaceReferencePreview = useCallback(
    (requestId: number, outcome: WorkspaceReferencePreviewOutcome): void => {
      const settled = settledWorkspaceReferenceRequestRef.current;
      if (settled?.requestId === requestId) {
        if (settled.outcome === outcome && outcome !== "opened") return;
        if (settled.outcome !== "opened") return;
        settledWorkspaceReferenceRequestRef.current = {
          ...settled,
          outcome,
          focusFrameId: outcome === "opened" ? settled.focusFrameId : undefined,
          focusCompleted: outcome === "opened" ? settled.focusCompleted : true,
          expectedFocus: outcome === "opened" ? settled.expectedFocus : null,
        };
      } else {
        settledWorkspaceReferenceRequestRef.current = {
          requestId,
          outcome,
          focusFrameId: undefined,
          focusCompleted: outcome !== "opened",
          expectedFocus: null,
        };
      }
      onWorkspaceReferencePreviewSettled(requestId, outcome);
    },
    [onWorkspaceReferencePreviewSettled],
  );
  /** 首次发现 Subagent 只补充总览入口，不切换当前 Tab 或抢夺输入焦点。 */
  const ensureAgentsTab = useCallback((): void => {
    if (!openTabs.includes("agents")) onOpenTabsChange([...openTabs, "agents"]);
  }, [onOpenTabsChange, openTabs]);
  const approvalPort = useMemo<TaskApprovalPort>(
    () => ({ respond: (input) => runtimeTurns.approvalRespond(input) }),
    [runtimeTurns],
  );
  const resumePort = useMemo<TaskResumePort>(
    () => ({
      /** Task 只转发既有 Turn identity；accepted 详情仍由随后的权威重读收敛。 */
      resume: async (input) => {
        await runtimeTurns.resumeTurn(input);
      },
    }),
    [runtimeTurns],
  );
  const closeTaskAttachmentPreviewsRef = useRef<
    (closedThreadIds: readonly string[]) => Promise<void>
  >(async () => undefined);
  const clearClosedTaskThreadsRef = useRef<
    (closedThreadIds: readonly string[], fence: TimelineRuntimeFence) => boolean
  >(() => false);
  /** ACK 已由 TaskController 验证；同一 Runtime fence 再约束 Thread 草稿与 Preview 清理。@author kongweiguang */
  const handleClosedTaskThreads = useCallback(
    async (closedThreadIds: readonly string[], fence: TimelineRuntimeFence): Promise<boolean> => {
      if (!clearClosedTaskThreadsRef.current(closedThreadIds, fence)) return false;
      if (activeTaskThreadId !== undefined && closedThreadIds.includes(activeTaskThreadId))
        onTabChange("agents");
      void closeTaskAttachmentPreviewsRef.current(closedThreadIds).catch(() => undefined);
      return true;
    },
    [activeTaskThreadId, onTabChange],
  );
  const tasks = useTaskController({
    rootThreadId,
    parentRevision: parentThreadRevision,
    activeTaskThreadId: parsedSelectedTask?.taskThreadId,
    visible: active && (selectedTab === "agents" || parsedSelectedTask !== undefined),
    port: taskPort,
    transcriptPort: taskTranscriptPort,
    applyTranscriptSnapshot: applyTaskTranscriptSnapshot,
    renamePort: taskThreadRenamePort,
    approvalPort,
    resumePort,
    onSubagentDiscovered: ensureAgentsTab,
    runtimeFence,
    onSideTaskClosed: handleClosedTaskThreads,
  });
  const selectedTask =
    parsedSelectedTask === undefined
      ? undefined
      : tasks.tasks.find((task) => task.taskThreadId === parsedSelectedTask.taskThreadId);
  const childThreadId = selectedTask?.taskThreadId;
  const childTaskIsSideTask = parsedSelectedTask?.taskKind === "side_task";
  const childClarification = useInteractionController({
    threadId: childThreadId,
    visible: active && childTaskIsSideTask && childThreadId !== undefined,
    port: taskInteractionPort,
  });
  const [childPlanDetailsByThread, setChildPlanDetailsByThread] = useState<
    Readonly<Record<string, boolean>>
  >({});
  const [childComposerFocusByThread, setChildComposerFocusByThread] = useState<
    Readonly<Record<string, number>>
  >({});
  const childPlanDetailsOpen =
    childThreadId === undefined ? false : childPlanDetailsByThread[childThreadId] === true;
  const childComposerFocusRequest =
    childThreadId === undefined ? undefined : childComposerFocusByThread[childThreadId];
  const taskDetail = tasks.detail;
  const childThread =
    taskDetail === undefined || taskDetail.thread.threadId !== childThreadId
      ? undefined
      : taskDetail.thread;
  // task/read 用 null 表示服务端尚未生成偏好；交互 controller 以 undefined 表示同一缺省态。
  const childPreferences = childThread?.preferences ?? undefined;
  const childTaskRef = useRef(selectedTask);
  childTaskRef.current = selectedTask;
  const childRecoveryScopeRef = useRef({
    active,
    childThreadId,
    sideTask: childTaskIsSideTask,
    closingTaskThreadId: tasks.closingTaskThreadId,
  });
  childRecoveryScopeRef.current = {
    active,
    childThreadId,
    sideTask: childTaskIsSideTask,
    closingTaskThreadId: tasks.closingTaskThreadId,
  };
  /**
   * Child 的恢复 admission 依据完整、同 revision 的 thread/read；Task 摘要 revision 与 Thread CAS
   * 无关，且首屏读取会漏掉当前问题或把分页边界混成不同的 source。
   */
  const readChildThreadRevision = useCallback(
    async (threadId: string): Promise<number> => {
      const expectedTaskThreadId = childTaskRef.current?.taskThreadId;
      const initialRuntime = useTimelineStore.getState();
      const expectedGeneration = initialRuntime.handshake.generation;
      const expectedServerInstanceId = initialRuntime.serverInstanceId;
      /** 每个分页返回后重新核对 Host/runtime fence，隐藏或换代后立即丢弃旧读。 */
      const isCurrentRead = (): boolean => {
        const scope = childRecoveryScopeRef.current;
        const runtime = useTimelineStore.getState();
        return (
          expectedGeneration > 0 &&
          expectedServerInstanceId !== undefined &&
          scope.active &&
          scope.sideTask &&
          scope.childThreadId === expectedTaskThreadId &&
          childTaskRef.current?.taskThreadId === expectedTaskThreadId &&
          runtime.handshake.phase === "ready" &&
          runtime.handshake.generation === expectedGeneration &&
          runtime.serverInstanceId === expectedServerInstanceId
        );
      };
      if (
        expectedTaskThreadId === undefined ||
        expectedTaskThreadId !== threadId ||
        !isCurrentRead()
      )
        throw new Error("side task transcript scope changed");
      for (let attempt = 0; attempt < 3; attempt += 1) {
        let cursor: string | undefined;
        let revision: number | undefined;
        let firstPage: Awaited<ReturnType<TaskTranscriptPort["read"]>> | undefined;
        const items = new Map<
          Awaited<ReturnType<TaskTranscriptPort["read"]>>["items"][number]["itemId"],
          Awaited<ReturnType<TaskTranscriptPort["read"]>>["items"][number]
        >();
        const cursors = new Set<string>();
        let changedDuringRead = false;
        for (let pageNumber = 0; pageNumber < MAX_CHILD_SNAPSHOT_PAGES; pageNumber += 1) {
          const snapshot = await taskTranscriptPort.read({
            threadId,
            limit: 200,
            ...(cursor === undefined ? {} : { cursor }),
          });
          if (!isCurrentRead() || snapshot.threadId !== expectedTaskThreadId)
            throw new Error("side task transcript identity changed");
          firstPage ??= snapshot;
          revision ??= snapshot.revision;
          if (snapshot.revision !== revision) {
            changedDuringRead = true;
            break;
          }
          for (const item of snapshot.items) {
            const prior = items.get(item.itemId);
            if (prior !== undefined && JSON.stringify(prior) !== JSON.stringify(item))
              throw new Error("side task transcript page conflicted");
            items.set(item.itemId, item);
          }
          if (snapshot.nextCursor === null) {
            const complete = {
              ...(firstPage ?? snapshot),
              items: [...items.values()],
              nextCursor: null,
            };
            if (!isCurrentRead()) throw new Error("side task transcript scope changed");
            const outcome = useTimelineStore
              .getState()
              .applySnapshot(complete, workspace.workspaceId);
            if (outcome !== "applied" && outcome !== "late")
              throw new Error("side task transcript did not converge");
            return complete.revision;
          }
          if (cursors.has(snapshot.nextCursor))
            throw new Error("side task transcript cursor repeated");
          cursors.add(snapshot.nextCursor);
          cursor = snapshot.nextCursor;
        }
        if (!changedDuringRead) throw new Error("side task transcript page budget exhausted");
      }
      throw new Error("side task transcript revision kept changing");
    },
    [taskTranscriptPort, workspace.workspaceId],
  );
  const followupTurn = tasks.followupTurn;
  const refreshTaskDetail = tasks.refreshDetail;
  const childTurnPort = useMemo<ConversationTurnPort>(
    () => ({
      /** 发送必须进入 TaskCoordinator，并把当前 child 作为发送者，避免默认回落到 root。 */
      submitTurn: async (input) => {
        const task = childTaskRef.current;
        if (task === undefined || input.threadId !== task.taskThreadId)
          throw new Error("side task unavailable");
        return followupTurn(task, input.content as TaskContentBlock[], task.taskThreadId);
      },
      /** 失败恢复仍是独立 Runtime command；明确核验 child identity，防止侧聊指向 root 会话。 */
      continueTurn: async (input) => {
        const task = childTaskRef.current;
        if (task === undefined || input.threadId !== task.taskThreadId)
          throw new Error("side task unavailable");
        return runtimeTurns.continueTurn(input);
      },
      reaskTurn: async (input) => {
        const task = childTaskRef.current;
        if (task === undefined || input.threadId !== task.taskThreadId)
          throw new Error("side task unavailable");
        return runtimeTurns.reaskTurn(input);
      },
      resumeTurn: runtimeTurns.resumeTurn,
      respondToolRecovery: runtimeTurns.respondToolRecovery,
      cancelTurn: runtimeTurns.cancelTurn,
      enqueueTurnInput: runtimeTurns.enqueueTurnInput,
      prioritizeTurnInput: runtimeTurns.prioritizeTurnInput,
      updateTurnInput: runtimeTurns.updateTurnInput,
      deleteTurnInput: runtimeTurns.deleteTurnInput,
      approvalRespond: runtimeTurns.approvalRespond,
    }),
    [runtimeTurns, followupTurn],
  );
  const childPreferencesPort = useMemo<ConversationPreferencesPort | undefined>(() => {
    if (taskPreferencesPort === undefined) return undefined;
    return {
      /** 与主会话一样只在明确 CAS 冲突时重试一次；使用实时 Thread revision，不借 Task revision。 */
      updatePreferences: async (next) => {
        const task = childTaskRef.current;
        const thread = childThread;
        if (task === undefined || thread?.threadId !== task.taskThreadId)
          throw new Error("side task preferences unavailable");
        for (let attempt = 0; attempt < 2; attempt += 1) {
          const snapshot = await taskTranscriptPort.read({ threadId: task.taskThreadId, limit: 1 });
          if (
            snapshot.threadId !== task.taskThreadId ||
            childTaskRef.current?.taskThreadId !== task.taskThreadId
          )
            throw new Error("side task preferences scope changed");
          try {
            await taskPreferencesPort.update({
              threadId: task.taskThreadId,
              expectedThreadRevision: snapshot.revision,
              ...next,
            });
            break;
          } catch (failure) {
            if (
              attempt !== 0 ||
              failure === null ||
              typeof failure !== "object" ||
              !("code" in failure) ||
              failure.code !== "CONFLICT"
            )
              throw failure;
          }
        }
        await refreshTaskDetail();
      },
    };
  }, [childThread, taskPreferencesPort, taskTranscriptPort, refreshTaskDetail]);
  const childPlan = usePlanController({
    ownerThreadId: childThreadId,
    visible:
      active && childTaskIsSideTask && childThreadId !== undefined && taskPlanPort !== undefined,
    // Plan 详情嵌入 child task，不能依赖全局 plan tab，否则切换到该 tab 会丢失 child identity。
    detailsVisible:
      active &&
      childTaskIsSideTask &&
      childPlanDetailsOpen &&
      childThreadId !== undefined &&
      taskPlanPort !== undefined,
    port: taskPlanPort ?? UNAVAILABLE_PLAN_PORT,
  });
  const { createPlan: createChildPlan } = childPlan;
  /** Plan 快照变化时保持 Composer 能力稳定，并在调用时校验子任务身份。 */
  const childPlanCreationPort = useMemo<ConversationPlanCreationPort | undefined>(() => {
    if (taskPlanPort === undefined || !childTaskIsSideTask) return undefined;
    return {
      /** Plan 首轮用 child Thread 的已读 revision 创建独立 artifact。 */
      create: async (threadId, objective, expectedThreadRevision) => {
        const task = childTaskRef.current;
        if (task === undefined || task.taskThreadId !== threadId) return false;
        return createChildPlan(threadId, objective, expectedThreadRevision);
      },
    };
  }, [createChildPlan, childTaskIsSideTask, taskPlanPort]);
  const childInteraction = useConversationInteractionController({
    threadId: childThreadId,
    workspaceId: workspace.workspaceId,
    preferences: childPreferences,
    models: taskModels,
    ready:
      active &&
      turnAdmissionReady &&
      (boot.status === "ready" || boot.status === "busy") &&
      childPreferencesPort !== undefined &&
      childThread !== undefined &&
      tasks.readyTaskThreadId === childThreadId,
    blocked: !active,
    turnPort: childTurnPort,
    readThreadRevision: readChildThreadRevision,
    planCreationPort: childPlanCreationPort,
    preferencesPort: childPreferencesPort ?? {
      updatePreferences: async () => {
        throw new Error("side task preferences unavailable");
      },
    },
    attachmentPort: taskAttachmentPort,
    onAttachmentRemoved,
    onAttachmentsBound,
  });
  clearClosedTaskThreadsRef.current = childInteraction.clearClosedThreads;
  const childNativeDrop = useComposerNativeDropRouter(
    taskNativeDropPort,
    active && childTaskIsSideTask && childThreadId !== undefined,
  );
  const creatingSideTaskRef = useRef(false);
  const createSideChatRef = useRef<((content?: string) => Promise<void>) | undefined>(undefined);
  const currentRootThreadRef = useRef(rootThreadId);
  const currentOpenTabsRef = useRef(openTabs);
  // 直接更新 opaque identity ref，使 render 后立即到达的 ACK 也能识别根会话切换。
  currentRootThreadRef.current = rootThreadId;
  currentOpenTabsRef.current = openTabs;

  /**
   * 侧聊创建必须携带发起它的 Thread 身份；主入口传 root，侧聊 Composer 传当前 child。
   * root 只用于校验新 Task 仍属于当前树，避免异步 ACK 在会话切换后留下孤儿会话。
   * 空侧聊的焦点意图等目标 Thread ready 且仍被当前可见 Tab 持有后才发出，避免首次挂载丢失或迟到抢焦。
   * @author kongweiguang
   */
  const createSideChatFromSource = useCallback(
    async (source: SideChatSource, content?: string): Promise<void> => {
      const expectedRootThreadId = rootThreadId;
      if (
        expectedRootThreadId === undefined ||
        source.threadId === "" ||
        creatingSideTaskRef.current
      )
        throw new Error("侧聊暂不可用，请稍后重试。");
      creatingSideTaskRef.current = true;
      try {
        const task = await tasks.createSideTask({
          taskName: "侧聊",
          sourceThreadId: source.threadId,
          sourceThreadRevision: source.revision,
          preferences:
            source.preferences === undefined
              ? undefined
              : {
                  providerId: source.preferences.providerId,
                  modelId: source.preferences.modelId,
                  reasoningLevel: source.preferences.reasoningLevel,
                  accessMode: source.preferences.accessMode,
                  collaborationMode: source.preferences.collaborationMode,
                },
        });
        const sourceStillActive =
          source.threadId === expectedRootThreadId ||
          childTaskRef.current?.taskThreadId === source.threadId;
        if (
          currentRootThreadRef.current !== expectedRootThreadId ||
          task.rootThreadId !== expectedRootThreadId ||
          !sourceStillActive
        ) {
          // ACK 已创建但来源 Host 已失效时必须先关闭临时 Thread，不能只丢弃 Tab 留下孤儿侧聊。
          try {
            await tasks.close(task);
          } catch {
            // 关闭失败时保留服务端错误边界；用户仍需获知清理未完成，不能伪装创建已回滚。
            toast.error("侧聊来源已切换，但临时会话未能清理，请稍后重试。", {
              id: "ja-task:create-cleanup",
            });
          }
          throw new Error("侧聊来源会话已切换，请重试。");
        }
        const tab = taskWorkbenchTab({ ...task, label: task.taskName });
        const retained = currentOpenTabsRef.current.filter((candidate) => candidate !== "new");
        if (!retained.includes(tab.key)) onOpenTabsChange([...retained, tab.key]);
        const normalizedContent = content?.trim() ?? "";
        if (normalizedContent === "") {
          const taskReady = tasks.waitForTaskReady(task.taskThreadId);
          onTabChange(tab.key);
          await taskReady;
          const focusScope = childRecoveryScopeRef.current;
          if (
            currentRootThreadRef.current !== expectedRootThreadId ||
            !currentOpenTabsRef.current.includes(tab.key) ||
            !focusScope.active ||
            !focusScope.sideTask ||
            focusScope.childThreadId !== task.taskThreadId ||
            focusScope.closingTaskThreadId === task.taskThreadId
          )
            return;
          setChildComposerFocusByThread((current) => ({
            ...current,
            [task.taskThreadId]: (current[task.taskThreadId] ?? 0) + 1,
          }));
          return;
        }
        // 在切换 Tab 前登记 ready waiter；Controller 会等目标 Thread 的首屏 read、observe
        // 和 ACK 后权威重读完成，避免 follow-up 落入创建前仍 active 的旧会话。
        const taskReady = tasks.waitForTaskReady?.(task.taskThreadId) ?? Promise.resolve();
        onTabChange(tab.key);
        await taskReady;
        await tasks.followup(task, [{ type: "text", text: normalizedContent }], source.threadId);
      } finally {
        creatingSideTaskRef.current = false;
      }
    },
    [onOpenTabsChange, onTabChange, rootThreadId, tasks],
  );

  const childSlashCommands = useMemo<readonly ComposerSlashCommand[]>(() => {
    if (childThreadId === undefined || !childTaskIsSideTask || childThread === undefined) return [];
    const sideChatCommand: ComposerSlashCommand = {
      id: "btw",
      name: "btw",
      aliases: ["侧聊"],
      label: "新建侧聊",
      description: "从当前侧聊打开一个临时侧聊",
      available: true,
      argument: { mode: "optional", label: "内容", placeholder: "描述要在侧聊处理的事情" },
      /** 当前 child 是唯一来源；命令不会读取或修改主 Thread 的偏好。 */
      execute: ({ argument }) =>
        createSideChatFromSource(
          {
            threadId: childThread.threadId,
            revision: childThread.revision,
            preferences: childPreferences,
          },
          argument,
        ),
    };
    return [
      sideChatCommand,
      ...(taskPlanPort === undefined
        ? []
        : conversationModeCommands({
            available: true,
            interaction: childInteraction,
          })),
    ];
  }, [
    childInteraction,
    childPreferences,
    childTaskIsSideTask,
    childThread,
    childThreadId,
    createSideChatFromSource,
    taskPlanPort,
  ]);

  /** Task 的 @ 搜索使用当前 child Thread 的 Workspace 授权，避免把子会话请求伪装成根会话。 */
  const searchTaskWorkspacePaths = useCallback(
    (query: string) => {
      if (childThreadId === undefined) return Promise.reject(new Error("task context unavailable"));
      return queryRuntime("workspace/path/search", {
        threadId: childThreadId,
        workspaceId: workspace.workspaceId,
        query,
        limit: 50,
      });
    },
    [childThreadId, queryRuntime, workspace.workspaceId],
  );
  /** Side Task Composer 只获得当前 child scope 的结构化草稿、模型目录与安全 Workspace 查询。 */
  const taskComposerEnvironment = useMemo<TaskComposerEnvironment>(
    () => ({
      workspaceId: workspace.workspaceId,
      runtimeGeneration: generation,
      usageReader: taskUsageReader,
      nativeDropEvent: childNativeDrop.event,
      dropZoneRef: childNativeDrop.registerDropZone,
      skills: taskComposerSkills,
      slashCommands: childSlashCommands,
      onRestoreDefaults:
        taskDefaultPreferences === undefined
          ? undefined
          : () =>
              childInteraction.resetPreferences(
                taskDefaultPreferences.selection,
                taskDefaultPreferences.accessMode,
              ),
      onOpenWorkspaceReference,
      attachmentPort: taskAttachmentPort,
      attachmentThumbnailPort: attachmentPreviewPort,
      onSearchWorkspacePaths: searchTaskWorkspacePaths,
      onOpenAttachmentPreview:
        onOpenAttachmentPreview === undefined
          ? undefined
          : (attachment, source) => {
              // Preview host 只签发当前可渲染的文本与图片类型；PDF/二进制仍保留附件事实但不伪造预览。
              if (attachment.mediaKind !== "text" && attachment.mediaKind !== "image") return;
              onOpenAttachmentPreview(
                {
                  attachmentId: attachment.attachmentId,
                  displayName: attachment.fileName,
                  mediaKind: attachment.mediaKind,
                  authorization: { kind: "draft" },
                },
                source,
              );
            },
      onOpenQueuedAttachmentPreview:
        childThreadId === undefined || onOpenAttachmentPreview === undefined
          ? undefined
          : (attachment, source) => {
              if (attachment.mediaKind !== "text" && attachment.mediaKind !== "image") return;
              onOpenAttachmentPreview(
                {
                  attachmentId: attachment.attachmentId,
                  displayName: attachment.fileName,
                  mediaKind: attachment.mediaKind,
                  authorization: { kind: "thread", threadId: childThreadId },
                },
                source,
              );
            },
    }),
    [
      generation,
      onOpenAttachmentPreview,
      searchTaskWorkspacePaths,
      taskAttachmentPort,
      attachmentPreviewPort,
      taskComposerSkills,
      taskUsageReader,
      taskDefaultPreferences,
      onOpenWorkspaceReference,
      childSlashCommands,
      childNativeDrop.event,
      childNativeDrop.registerDropZone,
      childThreadId,
      childInteraction,
      workspace.workspaceId,
    ],
  );
  /** Child Transcript 的链接、复制、Artifact 和附件预览复用现有安全 native 边界。 */
  const taskTranscriptActions = useMemo<TaskTranscriptActions>(
    () => ({
      onOpenLink: onOpenExternalUrl,
      onCopyText,
      onReadMessageContent: taskMessageContentRead,
      onReadAnswerContent: taskAnswerContentRead,
      onReadToolArtifact:
        taskArtifactPort === undefined
          ? undefined
          : (input) =>
              taskArtifactPort.readToolArtifact({ workspaceId: workspace.workspaceId, ...input }),
      /** 侧边会话复用主 Runtime controller，但必须先核对点击目标仍属于当前 Task Thread。 */
      onResolveToolRecovery: (input) => {
        const task = childTaskRef.current;
        if (task === undefined || input.threadId !== task.taskThreadId)
          return Promise.reject(new Error("side task recovery target changed"));
        return runtimeTurns.respondToolRecovery(input);
      },
      onOpenAttachmentPreview:
        onOpenAttachmentPreview === undefined
          ? undefined
          : (attachment, source) =>
              onOpenAttachmentPreview(
                {
                  attachmentId: attachment.attachmentId,
                  displayName: attachment.displayName,
                  mediaKind: attachment.mediaKind,
                  authorization: { kind: "thread", threadId: attachment.threadId },
                },
                source,
              ),
    }),
    [
      onCopyText,
      onOpenAttachmentPreview,
      onOpenExternalUrl,
      runtimeTurns,
      taskArtifactPort,
      taskMessageContentRead,
      taskAnswerContentRead,
      workspace.workspaceId,
    ],
  );

  const previousRootThreadRef = useRef(rootThreadId);
  /** 只有同一实例被显式重新绑定根身份才清理 Task，首次挂载不能擦除本会话自己的标签。 */
  useEffect(() => {
    if (previousRootThreadRef.current === rootThreadId) return;
    previousRootThreadRef.current = rootThreadId;
    setChildPlanDetailsByThread({});
    const retained = openTabs.filter((tab) => parseTaskWorkbenchTabKey(tab) === undefined);
    if (retained.length !== openTabs.length) {
      if (parseTaskWorkbenchTabKey(selectedTab) !== undefined) onTabChange(retained[0] ?? "agents");
      onOpenTabsChange(retained);
    }
    // openTabs 故意不参与：该 effect 只响应根身份变化，避免用户新开实例后被立即移除。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rootThreadId]);

  /** 按当前根树摘要恢复受控 descriptor；未知实例只保留最小安全标签等待 list 收敛。 */
  const describeTab = useCallback(
    (key: WorkbenchTabKey): WorkbenchTab => {
      const parsed = parseTaskWorkbenchTabKey(key);
      if (parsed === undefined) return capabilityWorkbenchTab(key as WorkbenchCapability);
      const summary = tasks.tasks.find((task) => task.taskThreadId === parsed.taskThreadId);
      if (summary !== undefined) return taskWorkbenchTab({ ...summary, label: summary.taskName });
      if (rootThreadId === undefined) return capabilityWorkbenchTab("agents");
      if (parsed.taskThreadId === undefined) return capabilityWorkbenchTab("agents");
      return taskWorkbenchTab({
        taskThreadId: parsed.taskThreadId,
        taskKind: parsed.taskKind,
        rootThreadId,
        label: parsed.taskKind === "subagent" ? "Subagent" : "侧聊",
      });
    },
    [rootThreadId, tasks.tasks],
  );
  const openTabDescriptors = useMemo(() => openTabs.map(describeTab), [describeTab, openTabs]);
  const selectedTabDescriptor = useMemo(() => describeTab(selectedTab), [describeTab, selectedTab]);

  /** 主 Composer 与右栏入口固定以当前 root 为来源，保留原有主任务控制语义。 */
  const createSideChat = useCallback(
    (content?: string): Promise<void> => {
      if (rootThreadId === undefined || parentThreadRevision === undefined)
        return Promise.reject(new Error("侧聊暂不可用，请稍后重试。"));
      return createSideChatFromSource(
        {
          threadId: rootThreadId,
          preferences: taskParentPreferences,
        },
        content,
      );
    },
    [createSideChatFromSource, parentThreadRevision, rootThreadId, taskParentPreferences],
  );

  createSideChatRef.current = createSideChat;

  /** 新建入口沿用统一侧聊流程；Workbench 的同步 callback 只负责启动并展示失败反馈。 */
  const createSideTask = useCallback((): WorkbenchTaskTab | undefined => {
    const expectedRootThreadId = rootThreadId;
    if (expectedRootThreadId === undefined || parentThreadRevision === undefined) return undefined;
    void createSideChat().catch(() => {
      toast.error("新建侧聊失败，请重试。", { id: "ja-task:create" });
    });
    return undefined;
  }, [createSideChat, parentThreadRevision, rootThreadId]);

  /** 只向当前会话注册最新闭包；宿主切换到其它 Thread 时由 SessionHost 清除旧入口。 */
  useEffect(() => {
    if (onRegisterSideChatLauncher === undefined) return undefined;
    const launcher = (content?: string): Promise<void> => {
      const current = createSideChatRef.current;
      return current === undefined
        ? Promise.reject(new Error("侧聊暂不可用，请稍后重试。"))
        : current(content);
    };
    onRegisterSideChatLauncher(launcher);
    return () => onRegisterSideChatLauncher(undefined);
  }, [onRegisterSideChatLauncher]);

  /** 持久 Task 标题复用 Thread rename CAS；创建入口不保留未持久化 Tab。 */
  const renameTaskTab = useCallback(
    async (tab: WorkbenchTaskTab, label: string): Promise<void> => {
      const normalized = label.trim();
      if (tab.taskKind !== "side_task" || normalized === "" || normalized.length > 96)
        throw new Error("invalid side task title");
      if (tab.taskThreadId === undefined) throw new Error("side task is unavailable");
      const task = tasks.tasks.find((candidate) => candidate.taskThreadId === tab.taskThreadId);
      if (task === undefined) throw new Error("side task is unavailable");
      await tasks.rename(task, normalized);
    },
    [tasks],
  );

  /** 总览点击只打开对应实例并聚焦；同一 key 已存在时不会复制 Tab。 */
  const openTask = useCallback(
    (task: TaskSummary): void => {
      const tab = taskWorkbenchTab({ ...task, label: task.taskName });
      if (!openTabs.includes(tab.key)) onOpenTabsChange([...openTabs, tab.key]);
      onTabChange(tab.key);
    },
    [onOpenTabsChange, onTabChange, openTabs],
  );
  const terminal = useTerminalWorkspaceLifecycle(
    workspace.workspaceId,
    adapters.terminal,
    openTabs.includes("terminal"),
    active && selectedTab === "terminal",
    onRegisterTerminalLifecycle,
  );
  usePreviewWorkspaceLifecycle(projection.previewWorkspaceLifecycle, onRegisterPreviewLifecycle);
  const previewPort = useMemo<PreviewPort>(
    () => ({
      openTarget: projection.preview.onOpenTarget,
      newPage: projection.preview.onNewPage,
      selectPage: projection.preview.onSelectPage,
      closePage: projection.preview.onClosePage,
      navigate: projection.preview.onNavigate,
      navigateFile: projection.preview.onNavigateFile,
      goBack: projection.preview.onGoBack,
      goForward: projection.preview.onGoForward,
      reload: projection.preview.onReload,
      retryRecovery: projection.preview.onRetryRecovery,
      changeViewport: projection.preview.onViewportChange,
    }),
    [
      projection.preview.onOpenTarget,
      projection.preview.onNewPage,
      projection.preview.onSelectPage,
      projection.preview.onClosePage,
      projection.preview.onNavigate,
      projection.preview.onNavigateFile,
      projection.preview.onGoBack,
      projection.preview.onGoForward,
      projection.preview.onReload,
      projection.preview.onRetryRecovery,
      projection.preview.onViewportChange,
    ],
  );
  const previewController = usePreviewController({
    url: projection.preview.url ?? "",
    loading: projection.preview.loading ?? false,
    recovering: projection.preview.recovering ?? false,
    error: projection.preview.error,
    active: active && selectedTab === "preview" && !tabContextMenuOpen,
    port: previewPort,
    pages: projection.preview.pages,
    activePageId: projection.preview.activePageId,
    canGoBack: projection.preview.canGoBack,
    canGoForward: projection.preview.canGoForward,
    attachmentTarget,
    attachmentPort: attachmentPreviewPort,
    onDismissAttachment,
  });
  closeTaskAttachmentPreviewsRef.current = previewController.actions.attachment.closeForThreads;
  /** 全局 close 事件用自身 SID/generation 验证共享清理，不能把 Task tree root 当作 Preview 归属。@author kongweiguang */
  useEffect(
    () =>
      subscribeTaskHostEvents((event) => {
        if (event.method !== "task/closed") return;
        if (!event.params.closedThreadIds.includes(event.params.taskThreadId)) return;
        const params = event.params as typeof event.params & {
          serverInstanceId?: unknown;
          generation?: unknown;
        };
        if (
          typeof params.serverInstanceId !== "string" ||
          !Number.isSafeInteger(params.generation) ||
          (params.generation as number) <= 0
        )
          return;
        const fence: TimelineRuntimeFence = {
          serverInstanceId: params.serverInstanceId,
          generation: params.generation as number,
        };
        // 同 root 的 TaskController 已在该事件监听中接受 close 并清理；避免丢失它关闭前的队列索引。
        if (event.params.rootThreadId === rootThreadId) return;
        if (!clearClosedTaskThreadsRef.current(event.params.closedThreadIds, fence)) return;
        if (
          activeTaskThreadId !== undefined &&
          event.params.closedThreadIds.includes(activeTaskThreadId) &&
          event.params.rootThreadId !== rootThreadId
        )
          onTabChange("agents");
        void closeTaskAttachmentPreviewsRef
          .current(event.params.closedThreadIds)
          .catch(() => undefined);
      }),
    [activeTaskThreadId, onTabChange, rootThreadId],
  );
  const review = useReviewController({
    catalogEnabled: active && openTabs.includes("review") && selectedTab === "review",
    workspaceId: workspace.workspaceId,
    generation,
    selectionScopeId: rootThreadId,
    snapshotEnabled:
      turnReviewTarget === undefined &&
      active &&
      openTabs.includes("review") &&
      selectedTab === "review",
    adapter: adapters.review,
  });
  /** Review ACK is an ordinary Turn admission; authoritative transcript refresh resolves any early event race. */
  const refreshAcceptedReviewThread = useCallback((accepted: AiReviewAccepted): void => {
    const timeline = useTimelineStore.getState();
    timeline.recordThreadMetadataRevision(accepted.threadId, accepted.threadRevision);
    timeline.requestThreadResync(accepted.threadId);
  }, []);
  const reviewIsVisible =
    turnReviewTarget === undefined &&
    active &&
    openTabs.includes("review") &&
    selectedTab === "review";
  const aiReview = useAiReviewController({
    active: reviewIsVisible,
    workspaceId: workspace.workspaceId,
    threadId: rootThreadId,
    threadRevision: rootThreadRevision,
    runtimeGeneration,
    serverInstanceId: runtimeServerInstanceId,
    source: review.viewModel.state.source,
    snapshotRevision: review.viewModel.state.snapshot?.revision,
    gateway: adapters.aiReview,
    cancelTurn: async (turnId) => {
      await runtimeTurns.cancelTurn({ turnId });
    },
    onTurnAccepted: refreshAcceptedReviewThread,
  });
  /** 修复 Turn 复用主运行时准入，并把报告引用作为普通用户文本留在可审计 Timeline。 */
  const submitReviewRepair = useCallback(
    (prompt: string) => {
      if (rootThreadId === undefined) return Promise.reject(new Error("review thread unavailable"));
      return runtimeTurns.submitTurn({
        threadId: rootThreadId,
        content: [{ type: "text", text: prompt }],
      });
    },
    [rootThreadId, runtimeTurns],
  );
  const currentGitBranch = review.viewModel.state.catalog?.currentBranch?.trim() || undefined;
  // 范围视图按 key 重挂载，触发器引用由 Thread Host 持有才能在旧菜单关闭时找到新入口。
  const reviewSourceTriggerRef = useRef<HTMLButtonElement>(null);
  const source = review.viewModel.state.source;
  const reviewNavigationKey =
    turnReviewTarget === undefined
      ? gitReviewNavigationKey(
          workspace.workspaceId,
          rootThreadId,
          source,
          review.viewModel.state.layerFilter,
        )
      : `${workspace.workspaceId}:turn:${turnReviewTarget.threadId}:${turnReviewTarget.turnId}`;
  const reviewNavigationState = reviewNavigationHints[reviewNavigationKey];
  /**
   * 导航提示只保留最近有限个范围；淘汰最早插入项避免长会话浏览大量 commit 后形成无界状态。
   * callback 绑定当前 key，使 Panel 卸载 cleanup 仍写回离开的范围而不是新范围。
   */
  const rememberReviewNavigation = useCallback(
    (navigation: ReviewNavigationState): void => {
      setReviewNavigationHints((current) => {
        const next = { ...current, [reviewNavigationKey]: navigation };
        const keys = Object.keys(next);
        if (keys.length <= MAX_REVIEW_NAVIGATION_SCOPES) return next;
        const [oldest] = keys;
        if (oldest !== undefined) delete next[oldest];
        return next;
      });
    },
    [reviewNavigationKey],
  );
  const workspaceReviewLabel =
    source.kind === "uncommitted"
      ? review.viewModel.state.layerFilter === "all"
        ? "未提交"
        : review.viewModel.state.layerFilter === "unstaged"
          ? "未暂存"
          : review.viewModel.state.layerFilter === "staged"
            ? "已暂存"
            : "未跟踪"
      : source.kind === "unstaged"
        ? "未暂存"
        : source.kind === "staged"
          ? "已暂存"
          : source.kind === "branch"
            ? (review.viewModel.state.catalog?.baseRefs.find(
                (entry) => entry.refId === source.refId,
              )?.label ?? source.refId)
            : `提交 ${source.commitId.slice(0, 8)}`;
  const sourceNavigation = (
    <ReviewSourceNavigation
      triggerRef={reviewSourceTriggerRef}
      currentLabel={
        turnReviewTarget === undefined ? workspaceReviewLabel : (turnReviewScopeLabel ?? "最后一轮")
      }
      turnSelected={turnReviewTarget !== undefined}
      retainedTurnLabel={
        retainedTurnReviewTarget === undefined ? undefined : (turnReviewScopeLabel ?? "最后一轮")
      }
      latestTurnAvailable={latestTurnReviewAvailable}
      viewModel={review.viewModel}
      actions={review.actions}
      onShowRetainedTurn={onShowRetainedTurnReview}
      onShowLatestTurn={onShowLatestTurnReview}
      onShowWorkspaceReview={onDismissTurnReview}
    />
  );

  /**
   * Review Catalog 是当前 workspace Git 上下文的只读 owner；向壳层发布同一分支投影，避免
   * Composer 重复查询。session workspace 不属于项目 Git 范围，保持缺失且不伪造项目能力。
   */
  useEffect(() => {
    onGitBranchChange(workspace.workspaceId, currentGitBranch);
  }, [currentGitBranch, onGitBranchChange, workspace.workspaceId]);

  /** 卸载能力时只清除同一 workspace 的分支投影，防止旧 Workbench 污染新范围。 */
  useEffect(
    () => () => onGitBranchChange(workspace.workspaceId, undefined),
    [onGitBranchChange, workspace.workspaceId],
  );

  const [terminalLayout, persistTerminalLayout] = useTerminalLayoutPersistence(
    workspace.workspaceId,
    terminalLayoutStorage,
  );

  /** Files controller 的脱敏失败按文案去重；具体读取结果只在当前 binding owner 仍有效时呈现恢复动作。 */
  const showFilesNotice = useCallback((message: string): void => {
    toast.error(message, { id: `ja-files:${message}` });
  }, []);
  const filesOperations = useMemo(() => {
    const operations = createNotifyingFilesOperations(adapters.workspace, showFilesNotice);
    return {
      ...operations,
      /** 保留原生 Files reader 的 4 MiB 边界；仅其真实超限/非文本结果出现 Explorer 恢复动作。 */
      readFile: async (input: Parameters<typeof operations.readFile>[0]) => {
        const request = workspaceReferencePreviewRequestRef.current;
        const readOwner = openTargetHostScopeRef.current;
        const ownedRequestId =
          request?.reference.kind === "file" &&
          request.reference.workspaceId === input.workspaceId &&
          request.reference.relativePath === input.relativePath &&
          handledWorkspaceReferenceRequestRef.current === request.requestId
            ? request.requestId
            : undefined;
        try {
          const result = await operations.readFile(input);
          const currentOwner = openTargetHostScopeRef.current;
          if (
            currentOwner.active &&
            currentOwner.workspaceId === input.workspaceId &&
            currentOwner.threadId === readOwner.threadId &&
            currentOwner.bindingRevision === readOwner.bindingRevision
          ) {
            const reason = workspaceReadRecoveryReason(result.kind, result.content);
            if (reason !== undefined)
              toast.error(reason, {
                id: `ja-files-read:${JSON.stringify([input.workspaceId, input.relativePath])}`,
                action: explorerRecoveryAction(() =>
                  adapters.preview.revealFile(input.relativePath, input.workspaceId),
                ),
              });
          }
          return result;
        } catch (error) {
          if (
            ownedRequestId !== undefined &&
            workspaceReferencePreviewRequestRef.current?.requestId === ownedRequestId
          )
            settleWorkspaceReferencePreview(ownedRequestId, "failed");
          throw error;
        }
      },
    };
  }, [adapters.preview, adapters.workspace, settleWorkspaceReferencePreview, showFilesNotice]);
  const files = useFilesController({
    workspaceId: workspace.workspaceId,
    operations: filesOperations,
    activityEnabled: active && openTabs.includes("files") && selectedTab === "files",
    onNotice: showFilesNotice,
    onRegisterLifecycle: onRegisterFilesLifecycle,
    ...filesBrowserControllerPorts,
  });

  /**
   * Workbench 激活 Files 后把一次性引用交给唯一 controller；路径预检和双重 workspace fence
   * 拒绝陈旧或越界意图，既不复制文件内容，也不创建第二个读取 owner。
   */
  useEffect(() => {
    const request = workspaceReferencePreviewRequest;
    if (request === undefined || handledWorkspaceReferenceRequestRef.current === request.requestId)
      return;
    if (
      request.reference.workspaceId !== workspace.workspaceId ||
      !isSafeWorkspaceReferencePath(request.reference.relativePath)
    ) {
      handledWorkspaceReferenceRequestRef.current = request.requestId;
      toast.error("该文件引用已失效，请从当前工作区重新添加。", {
        id: "ja-files:invalid-conversation-reference",
      });
      settleWorkspaceReferencePreview(request.requestId, "failed");
      return;
    }
    if (!active || selectedTab !== "files" || !openTabs.includes("files")) return;
    handledWorkspaceReferenceRequestRef.current = request.requestId;
    const node = workspaceReferenceNode(request);
    files.actions.selectNode(node);
  }, [
    active,
    files.actions,
    openTabs,
    selectedTab,
    settleWorkspaceReferencePreview,
    workspace.workspaceId,
    workspaceReferencePreviewRequest,
  ]);

  /**
   * @author kongweiguang
   * 文档 ready 后先同步记录本地 opened，使首帧前关闭也能结算；RAF 只提交焦点和全局 opened，
   * 并在重渲染清理后允许同一请求重排，避免丢失焦点提交或让失效 Host 抢焦点。
   */
  useEffect(() => {
    const request = workspaceReferencePreviewRequest;
    if (request === undefined || handledWorkspaceReferenceRequestRef.current !== request.requestId)
      return;
    const settled = settledWorkspaceReferenceRequestRef.current;
    if (
      settled?.requestId === request.requestId &&
      (settled.outcome !== "opened" || settled.focusCompleted || settled.focusFrameId !== undefined)
    )
      return;
    const currentScope = openTargetHostScopeRef.current;
    if (
      !active ||
      selectedTab !== "files" ||
      !openTabs.includes("files") ||
      request.reference.workspaceId !== workspace.workspaceId ||
      !currentScope.active ||
      currentScope.workspaceId !== request.reference.workspaceId ||
      currentScope.threadId !== rootThreadId
    ) {
      if (settled?.requestId === request.requestId && settled.outcome === "opened") {
        settledWorkspaceReferenceRequestRef.current = {
          ...settled,
          focusFrameId: undefined,
          focusCompleted: true,
        };
      }
      return;
    }
    const path = request.reference.relativePath;
    const ready =
      request.reference.kind === "directory"
        ? files.viewModel.selectedPath === path
        : files.viewModel.activePath === path && files.viewModel.documents?.[path] !== undefined;
    if (!ready) return;
    if (settled?.requestId !== request.requestId) {
      settledWorkspaceReferenceRequestRef.current = {
        requestId: request.requestId,
        outcome: "opened",
        focusFrameId: undefined,
        focusCompleted: false,
        expectedFocus:
          document.activeElement instanceof HTMLElement ? document.activeElement : null,
      };
    }
    const commit = (frameId?: number): void => {
      const current = settledWorkspaceReferenceRequestRef.current;
      if (
        workspaceReferencePreviewRequestRef.current?.requestId !== request.requestId ||
        current?.requestId !== request.requestId ||
        current.outcome !== "opened" ||
        current.focusCompleted ||
        current.focusFrameId !== frameId
      )
        return;
      const scope = openTargetHostScopeRef.current;
      if (
        !scope.active ||
        scope.workspaceId !== request.reference.workspaceId ||
        scope.threadId !== rootThreadId
      ) {
        settledWorkspaceReferenceRequestRef.current = {
          ...current,
          focusFrameId: undefined,
          focusCompleted: true,
        };
        return;
      }
      const expectedFocus = current.expectedFocus;
      const activeElement = document.activeElement;
      settledWorkspaceReferenceRequestRef.current = {
        ...current,
        focusFrameId: undefined,
        focusCompleted: true,
      };
      if (activeElement === expectedFocus || activeElement === document.body)
        focusWorkspaceReferenceInFiles(request, rootThreadId);
      settleWorkspaceReferencePreview(request.requestId, "opened");
    };
    if (typeof window.requestAnimationFrame !== "function") {
      commit();
      return;
    }
    const frame = window.requestAnimationFrame(() => commit(frame));
    const current = settledWorkspaceReferenceRequestRef.current;
    if (current?.requestId === request.requestId && current.outcome === "opened") {
      settledWorkspaceReferenceRequestRef.current = {
        ...current,
        focusFrameId: frame,
      };
    }
    return () => {
      window.cancelAnimationFrame(frame!);
      const pending = settledWorkspaceReferenceRequestRef.current;
      if (pending?.requestId === request.requestId && pending.focusFrameId === frame) {
        settledWorkspaceReferenceRequestRef.current = {
          ...pending,
          focusFrameId: undefined,
        };
      }
    };
  }, [
    active,
    files.viewModel.activePath,
    files.viewModel.documents,
    files.viewModel.selectedPath,
    openTabs,
    rootThreadId,
    selectedTab,
    settleWorkspaceReferencePreview,
    workspace.workspaceId,
    workspaceReferencePreviewRequest,
  ]);

  /** 单文件 Tab 的 X 移除真实 document 后结束本次引用预览，壳层据此关闭 Files 并恢复来源焦点。 */
  useEffect(() => {
    const request = workspaceReferencePreviewRequest;
    const settled = settledWorkspaceReferenceRequestRef.current;
    if (
      request?.reference.kind !== "file" ||
      request.reference.workspaceId !== workspace.workspaceId ||
      settled?.requestId !== request.requestId ||
      settled.outcome !== "opened" ||
      files.viewModel.documents?.[request.reference.relativePath] !== undefined
    )
      return;
    settleWorkspaceReferencePreview(request.requestId, "closed");
  }, [
    files.viewModel.documents,
    settleWorkspaceReferencePreview,
    workspace.workspaceId,
    workspaceReferencePreviewRequest,
  ]);

  /** 选择 Preview 或 Files 前先在唯一 shell state 中打开能力 Tab，保留既有会话标签顺序。 */
  const activateWorkbenchCapability = useCallback(
    (tab: "files" | "preview"): void => {
      if (onShowCapability !== undefined) {
        onShowCapability(tab);
        return;
      }
      if (!openTabs.includes(tab)) onOpenTabsChange([...openTabs, tab]);
      onTabChange(tab);
    },
    [onOpenTabsChange, onShowCapability, onTabChange, openTabs],
  );

  /** 只允许当前 Thread/绑定版本结算；Host 仅由 ThreadWorkbenchSessions 向当前会话提供请求。 */
  const isOpenTargetRequestCurrent = useCallback(
    (request: ConversationOpenTargetRequest): boolean => {
      const scope = openTargetHostScopeRef.current;
      const current = openTargetRequestRef.current;
      return (
        scope.workspaceId === request.workspaceId &&
        scope.threadId === request.threadId &&
        (request.bindingRevision === undefined ||
          request.bindingRevision === scope.bindingRevision) &&
        current?.requestId === request.requestId &&
        current.threadId === request.threadId &&
        current.workspaceId === request.workspaceId
      );
    },
    [],
  );

  /** 精确目标直接交给 Rust 核验，不搜索同名候选；迟到 resolve/open 不能覆盖新 Thread intent。 */
  const executeOpenTargetRequest = useCallback(
    async (request: ConversationOpenTargetRequest): Promise<void> => {
      const intentSequence = ++openTargetIntentSequenceRef.current;
      const isCurrentIntent = (): boolean =>
        openTargetIntentSequenceRef.current === intentSequence &&
        isOpenTargetRequestCurrent(request);
      if (!isCurrentIntent()) return;
      try {
        if (request.target.kind === "url") {
          activateWorkbenchCapability("preview");
          await previewController.actions.openTarget(request.target);
          if (!isCurrentIntent()) {
            if (processingOpenTargetRequestRef.current === request.requestId)
              processingOpenTargetRequestRef.current = undefined;
            return;
          }
          settledOpenTargetRequestRef.current = request.requestId;
          processingOpenTargetRequestRef.current = undefined;
          onOpenTargetSettled?.(request.requestId, "opened");
          return;
        }

        const targetPath = request.target.path;

        if (request.target.kind === "explorer") {
          await adapters.preview.revealFile(targetPath, request.workspaceId);
          if (!isCurrentIntent()) {
            if (processingOpenTargetRequestRef.current === request.requestId)
              processingOpenTargetRequestRef.current = undefined;
            return;
          }
          settledOpenTargetRequestRef.current = request.requestId;
          processingOpenTargetRequestRef.current = undefined;
          onOpenTargetSettled?.(request.requestId, "opened");
          return;
        }

        const previewMode =
          request.target.kind === "preview"
            ? "browser"
            : request.target.kind === "file"
              ? (request.target.previewMode ?? "editor")
              : "editor";
        const resolution = await adapters.preview.resolveFile(
          targetPath,
          request.workspaceId,
          request.target.line,
          request.target.column,
          previewMode,
        );
        if (!isCurrentIntent()) {
          if (processingOpenTargetRequestRef.current === request.requestId)
            processingOpenTargetRequestRef.current = undefined;
          return;
        }
        const line = resolution.line ?? request.target.line;
        const column = resolution.column ?? request.target.column;
        if (resolution.kind === "unsupported") {
          toast.error("此文件类型暂不支持在 Ja 中打开。", {
            id: OPEN_TARGET_TOAST_ID,
            action: {
              label: "在资源管理器中打开",
              onClick: () => {
                void adapters.preview
                  .revealFile(resolution.canonicalPath, request.workspaceId)
                  .catch(() => toast.error("无法在文件资源管理器中显示该路径。"));
              },
            },
          });
          settledOpenTargetRequestRef.current = request.requestId;
          processingOpenTargetRequestRef.current = undefined;
          onOpenTargetSettled?.(request.requestId, "failed");
          return;
        }
        if (resolution.kind === "directory") {
          if (resolution.withinWorkspace) {
            if (resolution.workspaceRelativePath === null)
              throw new Error("工作区目录路径无效，请重新打开项目后重试。");
            activateWorkbenchCapability("files");
            const revealed = await files.actions.revealDirectory(resolution.workspaceRelativePath);
            if (!isCurrentIntent()) {
              if (processingOpenTargetRequestRef.current === request.requestId)
                processingOpenTargetRequestRef.current = undefined;
              return;
            }
            if (!revealed) throw new Error("无法在文件树中定位该目录，请检查目录是否仍存在。");
          } else {
            toast.info("该目录位于当前项目之外。", {
              id: OPEN_TARGET_TOAST_ID,
              action: {
                label: "在文件资源管理器中打开",
                onClick: () => {
                  void adapters.preview
                    .revealFile(resolution.canonicalPath, request.workspaceId)
                    .catch(() => toast.error("无法在文件资源管理器中显示该目录。"));
                },
              },
            });
          }
          settledOpenTargetRequestRef.current = request.requestId;
          processingOpenTargetRequestRef.current = undefined;
          onOpenTargetSettled?.(request.requestId, "opened");
          return;
        }
        if (resolution.kind === "browser") {
          activateWorkbenchCapability("preview");
          await previewController.actions.openTarget({
            kind: "file",
            path: resolution.canonicalPath,
            previewMode,
            ...(line === null || line === undefined ? {} : { line }),
            ...(column === null || column === undefined ? {} : { column }),
          });
          if (!isCurrentIntent()) {
            if (processingOpenTargetRequestRef.current === request.requestId)
              processingOpenTargetRequestRef.current = undefined;
            return;
          }
          settledOpenTargetRequestRef.current = request.requestId;
          processingOpenTargetRequestRef.current = undefined;
          onOpenTargetSettled?.(request.requestId, "opened");
          return;
        }

        if (resolution.withinWorkspace && resolution.workspaceRelativePath === null)
          throw new Error("工作区文件路径无效，请重新打开项目后重试。");
        if (
          resolution.withinWorkspace &&
          !resolution.readOnly &&
          resolution.workspaceRelativePath !== null
        ) {
          activateWorkbenchCapability("files");
          const reveal =
            line === null || line === undefined
              ? undefined
              : { line, ...(column === null || column === undefined ? {} : { column }) };
          const opened = await files.actions.openPath(resolution.workspaceRelativePath, reveal);
          if (!isCurrentIntent()) {
            if (processingOpenTargetRequestRef.current === request.requestId)
              processingOpenTargetRequestRef.current = undefined;
            return;
          }
          if (!opened) {
            settledOpenTargetRequestRef.current = request.requestId;
            processingOpenTargetRequestRef.current = undefined;
            onOpenTargetSettled?.(request.requestId, "failed");
            return;
          }
        } else {
          if (resolution.content === null) {
            toast.error(
              resolution.truncated
                ? "文件超过 Ja 的读取上限，无法在 Ja 中显示正文。"
                : "Ja 无法读取此文本文件正文，可能是文件权限或编码不兼容。",
              {
                id: OPEN_TARGET_TOAST_ID,
                action: explorerRecoveryAction(() =>
                  adapters.preview.revealFile(resolution.canonicalPath, request.workspaceId),
                ),
              },
            );
            settledOpenTargetRequestRef.current = request.requestId;
            processingOpenTargetRequestRef.current = undefined;
            onOpenTargetSettled?.(request.requestId, "failed");
            return;
          }
          activateWorkbenchCapability("files");
          const opened = files.actions.openExternalDocument({
            path: resolution.canonicalPath,
            content: resolution.content,
            line: line ?? undefined,
            column: column ?? undefined,
            truncated: resolution.truncated,
            readOnlyReason: resolution.truncated
              ? "只读快照，内容已截断"
              : resolution.withinWorkspace
                ? "此文本文件以只读模式打开"
                : "工作区外文件，只读",
          });
          if (!opened || !isCurrentIntent()) {
            if (!isCurrentIntent()) return;
            settledOpenTargetRequestRef.current = request.requestId;
            processingOpenTargetRequestRef.current = undefined;
            onOpenTargetSettled?.(request.requestId, "failed");
            return;
          }
          if (resolution.truncated && isCurrentIntent())
            toast.info("文件超过 Ja 的读取上限，目前只显示部分内容。", {
              id: OPEN_TARGET_TOAST_ID,
              action: explorerRecoveryAction(() =>
                adapters.preview.revealFile(resolution.canonicalPath, request.workspaceId),
              ),
            });
        }
        settledOpenTargetRequestRef.current = request.requestId;
        processingOpenTargetRequestRef.current = undefined;
        onOpenTargetSettled?.(request.requestId, "opened");
      } catch (error: unknown) {
        if (!isCurrentIntent()) {
          if (processingOpenTargetRequestRef.current === request.requestId)
            processingOpenTargetRequestRef.current = undefined;
          return;
        }
        const message =
          error instanceof PreviewAdapterError
            ? error.message
            : request.target.kind === "url"
              ? "网页无法在 Ja 浏览器中打开，请检查地址后重试。"
              : request.target.kind === "explorer"
                ? "无法在文件资源管理器中显示该文件，请检查路径后重试。"
                : "文件无法打开，请检查路径和读取权限后重试。";
        toast.error(message, { id: OPEN_TARGET_TOAST_ID });
        settledOpenTargetRequestRef.current = request.requestId;
        processingOpenTargetRequestRef.current = undefined;
        onOpenTargetSettled?.(request.requestId, "failed");
      }
    },
    [
      activateWorkbenchCapability,
      adapters.preview,
      files.actions,
      isOpenTargetRequestCurrent,
      onOpenTargetSettled,
      previewController.actions,
    ],
  );

  /** 从 Files Markdown 发起的目标与回复路径共用 native resolver 和 click sequence fence。 */
  const openMarkdownFileTarget = useCallback(
    async (target: MarkdownFileTarget, _source: HTMLElement, mode?: "explorer" | "browser") => {
      const intent = ++openTargetIntentSequenceRef.current;
      const scope = openTargetHostScopeRef.current;
      const isCurrent = (): boolean =>
        openTargetIntentSequenceRef.current === intent &&
        openTargetHostScopeRef.current.workspaceId === scope.workspaceId &&
        openTargetHostScopeRef.current.threadId === scope.threadId &&
        openTargetHostScopeRef.current.bindingRevision === scope.bindingRevision;
      if (mode === "explorer") {
        await adapters.preview.revealFile(target.path, workspace.workspaceId);
        if (!isCurrent()) return;
        return;
      }
      const previewMode = mode === "browser" ? "browser" : "editor";
      const resolution = await adapters.preview.resolveFile(
        target.path,
        workspace.workspaceId,
        target.line,
        target.column,
        previewMode,
      );
      if (!isCurrent()) return;
      if (resolution.kind === "unsupported") {
        toast.error("此文件类型暂不支持在 Ja 中打开。", {
          action: {
            label: "在资源管理器中打开",
            onClick: () => {
              void adapters.preview
                .revealFile(resolution.canonicalPath, workspace.workspaceId)
                .catch(() => toast.error("无法在文件资源管理器中显示该路径。"));
            },
          },
        });
        return;
      }
      if (resolution.kind === "directory") {
        if (resolution.withinWorkspace) {
          if (resolution.workspaceRelativePath === null)
            throw new Error("工作区目录路径无效，请重新打开项目后重试。");
          activateWorkbenchCapability("files");
          if (!(await files.actions.revealDirectory(resolution.workspaceRelativePath)))
            throw new Error("无法在文件树中定位该目录，请检查目录是否仍存在。");
        } else {
          toast.info("该目录位于当前项目之外。", {
            action: {
              label: "在文件资源管理器中打开",
              onClick: () => {
                void adapters.preview
                  .revealFile(resolution.canonicalPath, workspace.workspaceId)
                  .catch(() => toast.error("无法在文件资源管理器中显示该目录。"));
              },
            },
          });
        }
        if (!isCurrent()) return;
        return;
      }
      if (resolution.kind === "browser") {
        activateWorkbenchCapability("preview");
        await previewController.actions.openTarget({
          kind: "file",
          path: resolution.canonicalPath,
          previewMode,
        });
        if (!isCurrent()) return;
        return;
      }
      if (resolution.withinWorkspace && resolution.workspaceRelativePath === null)
        throw new Error("工作区文件路径无效，请重新打开项目后重试。");
      if (resolution.withinWorkspace && !resolution.readOnly) {
        if (resolution.workspaceRelativePath === null)
          throw new Error("工作区文件路径无效，请重新打开项目后重试。");
        activateWorkbenchCapability("files");
        const opened = await files.actions.openPath(
          resolution.workspaceRelativePath,
          target.line === undefined
            ? undefined
            : {
                line: target.line,
                ...(target.column === undefined ? {} : { column: target.column }),
              },
        );
        if (!isCurrent()) return;
        if (!opened) throw new Error("文件未能在 Ja 编辑器中打开。");
      } else {
        if (resolution.content === null) {
          toast.error(
            resolution.truncated
              ? "文件超过 Ja 的读取上限，无法在 Ja 中显示正文。"
              : "Ja 无法读取此文本文件正文，可能是文件权限或编码不兼容。",
            {
              action: explorerRecoveryAction(() =>
                adapters.preview.revealFile(resolution.canonicalPath, workspace.workspaceId),
              ),
            },
          );
          return;
        }
        activateWorkbenchCapability("files");
        const opened = files.actions.openExternalDocument({
          path: resolution.canonicalPath,
          content: resolution.content,
          line: target.line,
          column: target.column,
          truncated: resolution.truncated,
          readOnlyReason: resolution.truncated ? "只读快照，内容已截断" : "工作区外文件，只读",
        });
        if (!opened && isCurrent()) throw new Error("只读文本快照未能打开。");
        if (opened && resolution.truncated && isCurrent())
          toast.info("文件超过 Ja 的读取上限，目前只显示部分内容。", {
            action: explorerRecoveryAction(() =>
              adapters.preview.revealFile(resolution.canonicalPath, workspace.workspaceId),
            ),
          });
      }
    },
    [
      activateWorkbenchCapability,
      adapters.preview,
      files.actions,
      openTargetIntentSequenceRef,
      previewController.actions,
      workspace.workspaceId,
    ],
  );

  /** Markdown HTTP(S) 链接仍使用当前 Thread 的隔离 Preview。 */
  const openMarkdownWebLink = useCallback(
    async (url: string): Promise<void> => {
      const intent = ++openTargetIntentSequenceRef.current;
      const scope = openTargetHostScopeRef.current;
      activateWorkbenchCapability("preview");
      await previewController.actions.openTarget({ kind: "url", url });
      if (
        openTargetIntentSequenceRef.current !== intent ||
        openTargetHostScopeRef.current.workspaceId !== scope.workspaceId ||
        openTargetHostScopeRef.current.threadId !== scope.threadId ||
        openTargetHostScopeRef.current.bindingRevision !== scope.bindingRevision
      )
        return;
    },
    [activateWorkbenchCapability, previewController.actions],
  );

  /** 新目标清除上一目标的错误提示；路径解析和回执始终绑定同一点击意图。 */
  useEffect(() => {
    const request = openTargetRequest;
    if (
      request === undefined ||
      !isOpenTargetRequestCurrent(request) ||
      settledOpenTargetRequestRef.current === request.requestId ||
      processingOpenTargetRequestRef.current === request.requestId
    )
      return;
    toast.dismiss(OPEN_TARGET_TOAST_ID);
    processingOpenTargetRequestRef.current = request.requestId;
    void executeOpenTargetRequest(request);
  }, [executeOpenTargetRequest, isOpenTargetRequestCurrent, openTargetRequest]);

  /**
   * 能力 Tab 关闭执行各自 teardown；只有临时侧聊需要服务端 close ACK，Subagent 仅关闭观察界面。
   * 这样用户收起子任务不会改变委派关系，而侧聊关闭才会进入临时生命周期终止流程。
   */
  const closeCapabilityTab = useCallback(
    (tab: WorkbenchTab): void | Promise<void> => {
      if (tab.kind === "task") {
        if (tab.taskKind !== "side_task") return;
        const detailTask = tasks.detail?.task;
        const task =
          (tab.taskThreadId === undefined
            ? undefined
            : tasks.tasks.find((candidate) => candidate.taskThreadId === tab.taskThreadId)) ??
          (detailTask?.taskThreadId === tab.taskThreadId ? detailTask : undefined);
        if (task === undefined) throw new Error("侧聊关闭失败，请重试。");
        return tasks.close(task);
      }
      if (tab.capability === "review") onDismissTurnReview();
      if (tab.capability === "files") return onCloseFilesCapability(workspace.workspaceId);
      if (tab.capability === "preview") {
        previewController.actions.attachment.dismiss();
        return projection.closePreview();
      }
      if (tab.capability === "terminal") return terminal.closeCapability();
    },
    [
      onCloseFilesCapability,
      onDismissTurnReview,
      previewController.actions.attachment,
      projection,
      tasks,
      terminal,
      workspace.workspaceId,
    ],
  );

  const terminalSlot = terminal.activated ? (
    <Suspense fallback={<LoadingState label="正在打开终端…" />}>
      <LazyTerminalWorkbenchSlot
        key={`${workspace.workspaceId}:${terminal.controllerGeneration}`}
        workspaceId={workspace.workspaceId}
        adapter={adapters.terminal}
        active={terminal.active}
        initialLayout={terminalLayout}
        onLayoutChange={persistTerminalLayout}
        onOpenExternalUrl={onOpenExternalUrl}
        onCopy={onCopyText}
        onRegisterCloseAll={terminal.registerCloseAll}
      />
    </Suspense>
  ) : (
    <span className="ja-visually-hidden" aria-hidden="true" />
  );

  return (
    <>
      <aside
        className="ja-inspector"
        aria-label="工作区面板"
        aria-hidden={active ? undefined : true}
        data-visible={active || undefined}
      >
        <Workbench
          selectedTab={selectedTabDescriptor}
          onTabChange={(tab) => onTabChange(tab.key)}
          openTabs={openTabDescriptors}
          onOpenTabsChange={(tabs) => onOpenTabsChange(tabs.map((tab) => tab.key))}
          onTabClose={closeCapabilityTab}
          onTabContextMenuOpenChange={setTabContextMenuOpen}
          onTaskTabRename={renameTaskTab}
          views={{
            review:
              turnReviewTarget === undefined ? (
                <ReviewPanelView
                  key={reviewNavigationKey}
                  viewModel={review.viewModel}
                  actions={review.actions}
                  onCopyText={onCopyText}
                  sourceNavigation={sourceNavigation}
                  navigationState={reviewNavigationState}
                  onNavigationStateChange={rememberReviewNavigation}
                  aiReview={aiReview}
                  onRepairFinding={submitReviewRepair}
                />
              ) : (
                <TurnReviewPanelView
                  key={reviewNavigationKey}
                  target={turnReviewTarget}
                  port={turnReviewPort}
                  active={active && selectedTab === "review"}
                  onShowWorkspaceReview={onDismissTurnReview}
                  sourceNavigation={sourceNavigation}
                  scopeLabel={turnReviewScopeLabel}
                  requestedPath={requestedTurnReviewPath}
                  requestedPathRevision={requestedTurnReviewPathRevision}
                  navigationState={reviewNavigationState}
                  onNavigationStateChange={rememberReviewNavigation}
                />
              ),
            files: (
              <FilesWorkspace
                viewModel={files.viewModel}
                actions={files.actions}
                onOpenMarkdownFile={openMarkdownFileTarget}
                onOpenMarkdownLink={openMarkdownWebLink}
                onCopyText={onCopyText}
                onAddToConversation={(node) => {
                  if (node.kind !== "file" && node.kind !== "directory") return;
                  onAddWorkspaceReference({
                    type: "workspace_reference",
                    workspaceId: workspace.workspaceId,
                    relativePath: node.path,
                    kind: node.kind,
                  });
                }}
              />
            ),
            terminal: terminalSlot,
            preview: (
              <PreviewPanelView
                viewModel={previewController.viewModel}
                actions={previewController.actions}
                onCopyText={onCopyText}
              />
            ),
            agents: (
              <SubagentOverview
                tasks={tasks.tasks}
                ownerThreadId={rootThreadId}
                loading={tasks.loading}
                error={tasks.error}
                onRefresh={tasks.refresh}
                onOpenTask={openTask}
              />
            ),
            plan:
              planAvailable && plan !== undefined ? (
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
                  onFinalizePlan={plan.finalizePlan}
                  onExecute={plan.execute}
                  onPausePlan={plan.pausePlan}
                  onResumePlan={plan.resumePlan}
                  onStopPlan={plan.stopPlan}
                  onReject={plan.reject}
                />
              ) : undefined,
          }}
          renderTaskView={(tab) => (
            <TaskDetailPanel
              tab={tab}
              active={active}
              controller={tasks}
              composerEnvironment={taskComposerEnvironment}
              transcriptActions={taskTranscriptActions}
              plan={parsedSelectedTask?.taskKind === "side_task" ? childPlan : undefined}
              clarification={
                parsedSelectedTask?.taskKind === "side_task" ? childClarification : undefined
              }
              planDetailsOpen={childPlanDetailsOpen}
              focusRequest={
                parsedSelectedTask?.taskKind === "side_task" ? childComposerFocusRequest : undefined
              }
              onPlanDetailsChange={
                parsedSelectedTask?.taskKind !== "side_task" || childThreadId === undefined
                  ? undefined
                  : (open) =>
                      setChildPlanDetailsByThread((current) => ({
                        ...current,
                        [childThreadId]: open,
                      }))
              }
              conversation={
                parsedSelectedTask?.taskKind === "side_task" ? childInteraction : undefined
              }
            />
          )}
          onCreateSideTask={
            rootThreadId !== undefined && parentThreadRevision !== undefined
              ? createSideTask
              : undefined
          }
          capabilityShortcuts={capabilityShortcuts}
          onClose={onClose}
        />
      </aside>
    </>
  );
}
