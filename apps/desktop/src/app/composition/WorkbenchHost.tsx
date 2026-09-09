// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactElement,
} from "react";
import { toast } from "sonner";
import {
  Workbench,
  capabilityWorkbenchTab,
  parseTaskWorkbenchTabKey,
  sideTaskDraftWorkbenchTab,
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
  type TaskApprovalPort,
  type TaskComposerEnvironment,
  type TaskComposerSkillSuggestion,
  type TaskPort,
  type TaskResumePort,
  type TaskSummary,
  type TaskThreadRenamePort,
  type TaskTranscriptActions,
  type TaskTranscriptPort,
} from "@/features/tasks";
import type { ConversationArtifactPort, ConversationAttachmentPort } from "@/features/conversation";
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
  useReviewController,
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
import { useRuntimeLifecycle, useRuntimeTurns } from "../RuntimeProvider";
import { PlanWorkbench, type GoalController } from "@/features/goals";
import type {
  ComposerWorkspaceReferenceTarget,
  WorkspaceReferencePreviewOutcome,
  WorkspaceReferencePreviewRequest,
} from "./ConversationWorkspace";
import { ReviewSourceNavigation } from "./ReviewSourceNavigation";
import { threadWorkbenchStorage } from "./threadWorkbenchStorage";

/** Terminal controller 与 xterm renderer 只在能力首次激活后加载。 */
const LazyTerminalWorkbenchSlot = lazy(async () => {
  const terminal = await import("./TerminalWorkbenchSlot");
  return { default: terminal.TerminalWorkbenchSlot };
});

const MAX_REVIEW_NAVIGATION_SCOPES = 16;

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

/** Files 投影提交后把焦点交给真实文件 Tab 或目录 TreeItem，目录只在当前确实折叠时展开。 */
function focusWorkspaceReferenceInFiles(request: WorkspaceReferencePreviewRequest): void {
  const { relativePath, kind } = request.reference;
  if (kind === "file") {
    const tab = [...document.querySelectorAll<HTMLElement>("[data-file-tab-path]")].find(
      (candidate) => candidate.dataset["fileTabPath"] === relativePath,
    );
    tab?.querySelector<HTMLButtonElement>('[role="tab"]')?.focus();
    return;
  }
  const row = [...document.querySelectorAll<HTMLElement>(".ja-file-tree-host [data-path]")].find(
    (candidate) => candidate.dataset["path"] === relativePath,
  );
  const treeItem = row?.closest<HTMLElement>('[role="treeitem"]');
  if (treeItem?.getAttribute("aria-expanded") === "false")
    row?.querySelector<HTMLButtonElement>(".ja-file-tree-disclosure")?.click();
  (treeItem ?? document.querySelector<HTMLElement>('.ja-file-tree-host [role="tree"]'))?.focus();
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
  readonly taskThreadRenamePort: TaskThreadRenamePort;
  readonly taskAttachmentPort?: ConversationAttachmentPort;
  readonly taskArtifactPort?: ConversationArtifactPort;
  readonly taskComposerSkills?: readonly TaskComposerSkillSuggestion[];
  readonly selectedTab: WorkbenchTabKey;
  readonly onTabChange: (tab: WorkbenchTabKey) => void;
  readonly openTabs: readonly WorkbenchTabKey[];
  readonly onOpenTabsChange: (tabs: readonly WorkbenchTabKey[]) => void;
  readonly capabilityShortcuts: Partial<Record<WorkbenchCapability, string>>;
  readonly onCopyText: (text: string) => Promise<void>;
  readonly onOpenExternalUrl: (url: string) => Promise<void>;
  readonly onClose: () => void;
  readonly onRegisterFilesLifecycle: (lifecycle: FilesWorkspaceLifecycle | undefined) => void;
  readonly onRegisterTerminalLifecycle: (lifecycle: TerminalWorkspaceLifecycle | undefined) => void;
  readonly onRegisterPreviewLifecycle: (lifecycle: PreviewWorkspaceLifecycle | undefined) => void;
  readonly onCloseFilesCapability: (workspaceId: string) => Promise<void>;
  readonly onAddWorkspaceReference: (reference: ComposerWorkspaceReferenceTarget) => void;
  readonly workspaceReferencePreviewRequest?: WorkspaceReferencePreviewRequest;
  readonly onWorkspaceReferencePreviewSettled: (
    requestId: number,
    outcome: WorkspaceReferencePreviewOutcome,
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
  readonly planGoalAvailable?: boolean;
  readonly goal?: GoalController;
}

/**
 * WorkbenchHost 持续挂载 feature controller 以保留编辑器、PTY 与 WebView 状态，但只在
 * Inspector 可见且 Review Tab 已打开并被选中时激活重型 Git snapshot。它只组合 view model
 * 与 lifecycle port；侧边任务也只在此处签发本地草稿身份，首次发送才进入持久化用例。
 * Tab 菜单或其它会话暂时遮挡原生网页时只暂停 viewport 可见性，不释放网页会话；
 * 布局与恢复 hint 使用根 Thread 命名空间，真实目录授权仍只由 Workspace 决定。
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
  taskThreadRenamePort,
  taskAttachmentPort,
  taskArtifactPort,
  taskComposerSkills,
  selectedTab,
  onTabChange,
  openTabs,
  onOpenTabsChange,
  capabilityShortcuts,
  onCopyText,
  onOpenExternalUrl,
  onClose,
  onRegisterFilesLifecycle,
  onRegisterTerminalLifecycle,
  onRegisterPreviewLifecycle,
  onCloseFilesCapability,
  onAddWorkspaceReference,
  workspaceReferencePreviewRequest,
  onWorkspaceReferencePreviewSettled,
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
  planGoalAvailable = false,
  goal,
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
  const { queryRuntime } = useRuntimeLifecycle();
  const workspaceReferencePreviewRequestRef = useRef(workspaceReferencePreviewRequest);
  const handledWorkspaceReferenceRequestRef = useRef<number | undefined>(undefined);
  const settledWorkspaceReferenceRequestRef = useRef<
    { requestId: number; outcome: WorkspaceReferencePreviewOutcome } | undefined
  >(undefined);
  const parsedSelectedTask = parseTaskWorkbenchTabKey(selectedTab);
  /** 通知回调和异步 Files 读取只读取最新请求，旧请求失败不能关闭较新的预览。 */
  useEffect(() => {
    workspaceReferencePreviewRequestRef.current = workspaceReferencePreviewRequest;
  }, [workspaceReferencePreviewRequest]);
  /** 同一 request 的每个阶段只结算一次；opened 后仍允许 closed 完成来源焦点恢复。 */
  const settleWorkspaceReferencePreview = useCallback(
    (requestId: number, outcome: WorkspaceReferencePreviewOutcome): void => {
      const settled = settledWorkspaceReferenceRequestRef.current;
      if (settled?.requestId === requestId && settled.outcome === outcome) return;
      if (settled?.requestId === requestId && settled.outcome !== "opened") return;
      settledWorkspaceReferenceRequestRef.current = { requestId, outcome };
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
  const tasks = useTaskController({
    rootThreadId,
    parentRevision: parentThreadRevision,
    activeTaskThreadId: parsedSelectedTask?.taskThreadId,
    visible: active && (selectedTab === "agents" || parsedSelectedTask !== undefined),
    port: taskPort,
    transcriptPort: taskTranscriptPort,
    renamePort: taskThreadRenamePort,
    approvalPort,
    resumePort,
    onSubagentDiscovered: ensureAgentsTab,
  });
  const [draftTaskLabels, setDraftTaskLabels] = useState<Readonly<Record<string, string>>>({});

  /** Task 的 @ 搜索仍以当前根 Thread 做 Workspace 授权，返回值保留迟到响应 identity。 */
  const searchTaskWorkspacePaths = useCallback(
    (query: string) => {
      if (rootThreadId === undefined) return Promise.reject(new Error("task context unavailable"));
      return queryRuntime("workspace/path/search", {
        threadId: rootThreadId,
        workspaceId: workspace.workspaceId,
        query,
        limit: 50,
      });
    },
    [queryRuntime, rootThreadId, workspace.workspaceId],
  );
  /** Side Task Composer 只获得结构化草稿所需能力，不能访问模型偏好或通用 Runtime tunnel。 */
  const taskComposerEnvironment = useMemo<TaskComposerEnvironment>(
    () => ({
      workspaceId: workspace.workspaceId,
      runtimeGeneration: generation,
      skills: taskComposerSkills,
      attachmentPort: taskAttachmentPort,
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
      onAttachmentRemoved,
      onAttachmentsBound,
    }),
    [
      generation,
      onAttachmentRemoved,
      onAttachmentsBound,
      onOpenAttachmentPreview,
      searchTaskWorkspacePaths,
      taskAttachmentPort,
      taskComposerSkills,
      workspace.workspaceId,
    ],
  );
  /** Child Transcript 的链接、复制、Artifact 和附件预览复用现有安全 native 边界。 */
  const taskTranscriptActions = useMemo<TaskTranscriptActions>(
    () => ({
      onOpenLink: onOpenExternalUrl,
      onCopyText,
      onReadToolArtifact:
        taskArtifactPort === undefined
          ? undefined
          : (input) =>
              taskArtifactPort.readToolArtifact({ workspaceId: workspace.workspaceId, ...input }),
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
      taskArtifactPort,
      workspace.workspaceId,
    ],
  );

  const previousRootThreadRef = useRef(rootThreadId);
  /** 只有同一实例被显式重新绑定根身份才清理 Task，首次挂载不能擦除本会话自己的标签。 */
  useEffect(() => {
    if (previousRootThreadRef.current === rootThreadId) return;
    previousRootThreadRef.current = rootThreadId;
    setDraftTaskLabels({});
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
      if (parsed.taskThreadId === undefined) {
        const draftId = key.slice("side-task:draft_".length);
        const draft = sideTaskDraftWorkbenchTab(rootThreadId, draftId);
        return { ...draft, label: draftTaskLabels[key] ?? draft.label };
      }
      return taskWorkbenchTab({
        taskThreadId: parsed.taskThreadId,
        taskKind: parsed.taskKind,
        rootThreadId,
        label: parsed.taskKind === "subagent" ? "Subagent" : "侧边任务",
      });
    },
    [draftTaskLabels, rootThreadId, tasks.tasks],
  );
  const openTabDescriptors = useMemo(() => openTabs.map(describeTab), [describeTab, openTabs]);
  const selectedTabDescriptor = useMemo(() => describeTab(selectedTab), [describeTab, selectedTab]);

  /** 新建只签发本地草稿 identity；task/create 留到详情首次发送。 */
  const createSideTaskDraft = useCallback((): WorkbenchTaskTab | undefined => {
    if (rootThreadId === undefined || parentThreadRevision === undefined) return undefined;
    const draftId = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
    return sideTaskDraftWorkbenchTab(rootThreadId, draftId);
  }, [parentThreadRevision, rootThreadId]);

  /** 创建 ACK 后先选择稳定 Child key，再替换草稿，避免偏好归一化把活动草稿回插。 */
  const replaceDraft = useCallback(
    (draft: WorkbenchTaskTab, task: TaskSummary): void => {
      const created = taskWorkbenchTab({ ...task, label: task.taskName });
      setDraftTaskLabels((current) => {
        if (current[draft.key] === undefined) return current;
        const next = { ...current };
        delete next[draft.key];
        return next;
      });
      onTabChange(created.key);
      onOpenTabsChange(openTabs.map((key) => (key === draft.key ? created.key : key)));
    },
    [onOpenTabsChange, onTabChange, openTabs],
  );

  /** 草稿只更新当前进程 label；持久 Task 交给 controller 复用 Thread rename CAS。 */
  const renameTaskTab = useCallback(
    async (tab: WorkbenchTaskTab, label: string): Promise<void> => {
      const normalized = label.trim();
      if (tab.taskKind !== "side_task" || normalized === "" || normalized.length > 96)
        throw new Error("invalid side task title");
      if (tab.taskThreadId === undefined) {
        setDraftTaskLabels((current) => ({ ...current, [tab.key]: normalized }));
        return;
      }
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
      navigate: projection.preview.onNavigate,
      reload: projection.preview.onReload,
      retryRecovery: projection.preview.onRetryRecovery,
      changeViewport: projection.preview.onViewportChange,
    }),
    [
      projection.preview.onNavigate,
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
    attachmentTarget,
    attachmentPort: attachmentPreviewPort,
    onDismissAttachment,
  });
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
  const currentGitBranch = review.viewModel.state.catalog?.currentBranch?.trim() || undefined;
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
   * Composer 重复查询。general workspace 非仓库时自然保持缺失，不伪造项目能力。
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

  /** Files controller 的脱敏失败按文案去重，具体请求归属由 readFile wrapper 的 identity 判定。 */
  const showFilesNotice = useCallback((message: string): void => {
    toast.error(message, { id: `ja-files:${message}` });
  }, []);
  const filesOperations = useMemo(() => {
    const operations = createNotifyingFilesOperations(adapters.workspace, showFilesNotice);
    return {
      ...operations,
      /** 在同一次真实 Files 读取外层冻结 request identity，迟到 rejection 不能误结算后续点击。 */
      readFile: async (input: Parameters<typeof operations.readFile>[0]) => {
        const request = workspaceReferencePreviewRequestRef.current;
        const ownedRequestId =
          request?.reference.kind === "file" &&
          request.reference.workspaceId === input.workspaceId &&
          request.reference.relativePath === input.relativePath &&
          handledWorkspaceReferenceRequestRef.current === request.requestId
            ? request.requestId
            : undefined;
        try {
          return await operations.readFile(input);
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
  }, [adapters.workspace, settleWorkspaceReferencePreview, showFilesNotice]);
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

  /** 文件只在真实 readFile 投影进入 document 后成功；目录选择提交后才安排可取消的焦点转移。 */
  useEffect(() => {
    const request = workspaceReferencePreviewRequest;
    if (
      request === undefined ||
      handledWorkspaceReferenceRequestRef.current !== request.requestId ||
      settledWorkspaceReferenceRequestRef.current?.requestId === request.requestId
    )
      return;
    if (
      !active ||
      selectedTab !== "files" ||
      !openTabs.includes("files") ||
      request.reference.workspaceId !== workspace.workspaceId
    )
      return;
    const path = request.reference.relativePath;
    const ready =
      request.reference.kind === "directory"
        ? files.viewModel.selectedPath === path
        : files.viewModel.activePath === path && files.viewModel.documents?.[path] !== undefined;
    if (!ready) return;
    const commit = (): void => {
      if (workspaceReferencePreviewRequestRef.current?.requestId !== request.requestId) return;
      focusWorkspaceReferenceInFiles(request);
      settleWorkspaceReferencePreview(request.requestId, "opened");
    };
    if (typeof window.requestAnimationFrame !== "function") {
      commit();
      return;
    }
    const frame = window.requestAnimationFrame(commit);
    return () => window.cancelAnimationFrame(frame);
  }, [
    active,
    files.viewModel.activePath,
    files.viewModel.documents,
    files.viewModel.selectedPath,
    openTabs,
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

  /** 显式 capability 关闭执行 teardown；关闭未持久草稿同时释放其进程期标签。 */
  const closeCapabilityTab = useCallback(
    (tab: WorkbenchTab): void | Promise<void> => {
      if (tab.kind === "task") {
        if (tab.taskThreadId === undefined)
          setDraftTaskLabels((current) => {
            if (current[tab.key] === undefined) return current;
            const next = { ...current };
            delete next[tab.key];
            return next;
          });
        return;
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
            />
          ),
          agents: (
            <SubagentOverview
              tasks={tasks.tasks}
              loading={tasks.loading}
              error={tasks.error}
              onRefresh={tasks.refresh}
              onOpenTask={openTask}
            />
          ),
          plan:
            planGoalAvailable && goal !== undefined ? (
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
                onApprove={goal.approve}
                onExecute={goal.execute}
                onAttachPlan={
                  goal.model === undefined ||
                  goal.planModel === undefined ||
                  (goal.model.goal.status !== "active" && goal.model.goal.status !== "paused") ||
                  goal.model.goal.activePlanId === goal.planModel.plan.planId
                    ? undefined
                    : goal.attachPlan
                }
                onDetachPlan={goal.model?.goal.activePlanId === null ? undefined : goal.detachPlan}
                onReject={goal.reject}
                onPause={goal.pause}
                onResume={goal.resume}
                onBeginEdit={
                  goal.model?.goal.status === "active" &&
                  goal.model.goal.activePlanId === goal.planModel?.plan.planId
                    ? goal.pause
                    : undefined
                }
                onCancelEdit={goal.resume}
                onContinue={goal.resume}
                onRespondInput={goal.respondInput}
              />
            ) : undefined,
        }}
        renderTaskView={(tab) => (
          <TaskDetailPanel
            tab={tab}
            controller={tasks}
            onCreated={(task) => replaceDraft(tab, task)}
            composerEnvironment={taskComposerEnvironment}
            transcriptActions={taskTranscriptActions}
          />
        )}
        onCreateSideTask={createSideTaskDraft}
        capabilityShortcuts={capabilityShortcuts}
        onClose={onClose}
      />
    </aside>
  );
}
