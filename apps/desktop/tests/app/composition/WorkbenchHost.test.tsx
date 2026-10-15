// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { Profiler, type ComponentProps } from "react";
import { toast } from "sonner";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WorkbenchHost } from "@/app/composition/WorkbenchHost";
import { PreviewAdapterError } from "@/api/tauri/preview";
import type { PlanController } from "@/features/plans";
import {
  useTimelineStore,
  type TimelineRuntimeFence,
  type TimelineSnapshot,
} from "@/features/conversation";
import { publishTaskHostEvent, type TaskReadModel, type TaskSummary } from "@/features/tasks";

const mocks = vi.hoisted(() => ({
  queryRuntime: vi.fn(),
  previewOpenTarget: vi.fn(),
  filesRead: vi.fn(),
  useReviewController: vi.fn(),
  useAiReviewController: vi.fn(),
  submitReviewTurn: vi.fn(),
  cancelReviewTurn: vi.fn(),
  usePreviewController: vi.fn(),
  useFilesController: vi.fn(),
  useJaWorkbench: vi.fn(),
  useTerminalWorkspaceLifecycle: vi.fn(),
  useTaskController: vi.fn(),
  taskEventListeners: new Set<(event: unknown) => void>(),
  TaskDetailPanel: vi.fn(() => null),
  Workbench: vi.fn((props: unknown) => {
    const workbenchProps = props as {
      selectedTab?: { kind: string };
      renderTaskView?: (tab: unknown) => unknown;
    };
    if (workbenchProps.selectedTab?.kind !== "task" || workbenchProps.renderTaskView === undefined)
      return null;
    return workbenchProps.renderTaskView(workbenchProps.selectedTab);
  }),
  FilesWorkspace: vi.fn((props: unknown) => {
    void props;
    return null;
  }),
}));

vi.mock("sonner", () => ({ toast: { error: vi.fn(), info: vi.fn(), dismiss: vi.fn() } }));
vi.mock("@/features/workbench", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/workbench")>()),
  Workbench: mocks.Workbench,
}));
vi.mock("@/features/tasks", () => ({
  SubagentOverview: () => null,
  TaskDetailPanel: mocks.TaskDetailPanel,
  useTaskController: mocks.useTaskController,
  subscribeTaskHostEvents: (listener: (event: unknown) => void) => {
    mocks.taskEventListeners.add(listener);
    return () => {
      mocks.taskEventListeners.delete(listener);
    };
  },
  publishTaskHostEvent: (event: unknown) => {
    for (const listener of mocks.taskEventListeners) listener(event);
  },
}));
vi.mock("@/app/RuntimeProvider", () => ({
  useRuntimeLifecycle: () => ({ queryRuntime: mocks.queryRuntime }),
  useRuntimeState: () => ({ boot: { status: "ready" }, turnAdmissionReady: true }),
  useRuntimeTurns: () => ({
    approvalRespond: vi.fn(),
    cancelTurn: mocks.cancelReviewTurn,
    resumeTurn: vi.fn(),
    continueTurn: vi.fn(),
    reaskTurn: vi.fn(),
    submitTurn: mocks.submitReviewTurn,
  }),
}));
vi.mock("@/features/workbench/files", () => ({
  FilesWorkspace: mocks.FilesWorkspace,
  useFilesController: mocks.useFilesController,
}));
vi.mock("@/features/workbench/preview", () => ({
  MediaPreviewSessionHintStorage: class {},
  PreviewPanelView: () => null,
  usePreviewController: mocks.usePreviewController,
}));
vi.mock("@/features/workbench/review", () => ({
  ReviewPanelView: () => null,
  TurnReviewPanelView: () => null,
  useAiReviewController: mocks.useAiReviewController,
  useReviewController: mocks.useReviewController,
}));
vi.mock("@/features/workbench/terminal", () => ({
  LocalTerminalLayoutStorage: class {},
  useTerminalLayoutPersistence: () => [{ kind: "single", rootPaneId: "pane_1" }, vi.fn()],
}));
vi.mock("@/app/application/createNotifyingFilesOperations", () => ({
  createNotifyingFilesOperations: () => ({ readFile: mocks.filesRead }),
}));
vi.mock("@/app/application/usePreviewWorkspaceLifecycle", () => ({
  usePreviewWorkspaceLifecycle: vi.fn(),
}));
vi.mock("@/app/application/useTerminalWorkspaceLifecycle", () => ({
  useTerminalWorkspaceLifecycle: mocks.useTerminalWorkspaceLifecycle,
}));
vi.mock("@/app/useJaWorkbench", () => ({ useJaWorkbench: mocks.useJaWorkbench }));
vi.mock("@/app/composition/filesBrowserControllerPorts", () => ({
  filesBrowserControllerPorts: {},
}));

afterEach(() => {
  cleanup();
  mocks.taskEventListeners.clear();
  useTimelineStore.getState().reset();
  vi.clearAllMocks();
  mocks.filesRead.mockReset();
});

beforeEach(() => {
  mocks.useReviewController.mockReturnValue({
    viewModel: {
      state: { catalog: undefined, source: { kind: "uncommitted" }, layerFilter: "all" },
      sourceOptions: [{ kind: "uncommitted" }],
    },
    actions: { setSource: vi.fn(), setLayerFilter: vi.fn() },
  });
  mocks.useAiReviewController.mockReturnValue({
    state: { runs: [] },
    selectedRun: undefined,
    ready: false,
    busy: false,
    actions: {
      start: vi.fn(async () => undefined),
      retry: vi.fn(async () => undefined),
      refresh: vi.fn(async () => undefined),
      cancel: vi.fn(async () => undefined),
      select: vi.fn(),
    },
  });
  mocks.usePreviewController.mockReturnValue({
    viewModel: {},
    actions: {
      attachment: { dismiss: vi.fn(), closeForThreads: vi.fn(async () => undefined) },
      openTarget: mocks.previewOpenTarget,
    },
  });
  mocks.previewOpenTarget.mockResolvedValue(undefined);
  mocks.queryRuntime.mockResolvedValue({ items: [], truncated: false });
  mocks.useFilesController.mockReturnValue({
    viewModel: { documents: {}, openPaths: [] },
    actions: {
      selectNode: vi.fn(),
      toggleDirectory: vi.fn(),
      openPath: vi.fn(async () => true),
      revealDirectory: vi.fn(async () => true),
      openExternalDocument: vi.fn(() => true),
    },
  });
  mocks.useTerminalWorkspaceLifecycle.mockReturnValue({
    activated: false,
    active: false,
    controllerGeneration: 0,
    closeCapability: vi.fn(),
    registerCloseAll: vi.fn(),
  });
  mocks.useJaWorkbench.mockReturnValue({
    onTabChange: vi.fn(),
    closePreview: vi.fn(),
    previewWorkspaceLifecycle: undefined,
    preview: {
      url: "",
      loading: false,
      recovering: false,
      onNavigate: vi.fn(),
      onReload: vi.fn(),
      onRetryRecovery: vi.fn(),
      onViewportChange: vi.fn(),
    },
  });
  mocks.useTaskController.mockReturnValue({
    tasks: [],
    loading: false,
    detailLoading: false,
    refresh: vi.fn(),
    rename: vi.fn(),
    cancel: vi.fn(),
  });
});

/** 构造仅供 composition 激活语义测试使用的最小真实属性面。 */
function makeProps(
  overrides: Partial<ComponentProps<typeof WorkbenchHost>> = {},
): ComponentProps<typeof WorkbenchHost> {
  return {
    workspace: { workspaceId: "ws_demo" } as ComponentProps<typeof WorkbenchHost>["workspace"],
    generation: 7,
    adapters: {} as ComponentProps<typeof WorkbenchHost>["adapters"],
    active: false,
    taskPort: {} as ComponentProps<typeof WorkbenchHost>["taskPort"],
    taskTranscriptPort: {} as ComponentProps<typeof WorkbenchHost>["taskTranscriptPort"],
    taskThreadRenamePort: {
      rename: vi.fn(async ({ threadId, title, expectedThreadRevision }) => ({
        threadId,
        title,
        revision: expectedThreadRevision + 1,
      })),
    },
    turnReviewPort: {
      readFrozen: vi.fn(),
    },
    selectedTab: "review",
    onTabChange: vi.fn(),
    openTabs: ["review"],
    onOpenTabsChange: vi.fn(),
    capabilityShortcuts: {},
    onCopyText: vi.fn(async () => undefined),
    onOpenExternalUrl: vi.fn(async () => undefined),
    onClose: vi.fn(),
    onRegisterFilesLifecycle: vi.fn(),
    onRegisterTerminalLifecycle: vi.fn(),
    onRegisterPreviewLifecycle: vi.fn(),
    onCloseFilesCapability: vi.fn(async () => undefined),
    onAddWorkspaceReference: vi.fn(),
    onWorkspaceReferencePreviewSettled: vi.fn(),
    onGitBranchChange: vi.fn(),
    latestTurnReviewAvailable: false,
    onShowRetainedTurnReview: vi.fn(),
    onShowLatestTurnReview: vi.fn(),
    onDismissTurnReview: vi.fn(),
    ...overrides,
  };
}

/** 关闭与普通发送都依赖 Store 的权威 ready 身份；组件层 Runtime mock 不能替代真实投影边界。@author kongweiguang */
function establishReadyTimelineRuntime(
  serverInstanceId = "srv_workbench_test",
  generation = 7,
): TimelineRuntimeFence {
  const outcome = useTimelineStore.getState().applyRuntimeStatus({
    status: "ready",
    serverInstanceId,
    generation,
  });
  if (outcome !== "applied") throw new Error("WorkbenchHost test Runtime fixture was rejected");
  return { serverInstanceId, generation };
}

/** 复用空侧聊创建与 ready 焦点竞态所需的最小持久摘要。@author kongweiguang */
function idleFocusSideTask(): TaskSummary {
  return {
    taskThreadId: "thr_child",
    parentThreadId: "thr_root",
    rootThreadId: "thr_root",
    originTurnId: null,
    taskName: "侧边任务",
    depth: 1,
    taskKind: "side_task",
    lifecycle: "independent",
    state: "idle",
    revision: 1,
    latestActivitySequence: 1,
    unreadCount: 0,
    descendantCount: 0,
    runningDescendantCount: 0,
    needsAttentionCount: 0,
    latestSafeSummary: "已创建",
    startedAt: null,
    completedAt: null,
    updatedAt: "2026-09-07T10:00:00Z",
  };
}

/** 真实 Timeline store 订阅 probe：用稳定字符串选择器和提交回调验证 snapshot 更新确实提交了重渲染。 */
function TimelineSubscriptionProbe({
  threadId,
  onCommit,
}: {
  threadId: string;
  onCommit: () => void;
}) {
  const signature = useTimelineStore((state) => {
    const itemId = state.itemIdsByThread[threadId]?.at(-1);
    const item = itemId === undefined ? undefined : state.items[itemId];
    return `${state.threadRevisionByThread[threadId] ?? "none"}|${item?.text ?? ""}`;
  });
  return (
    <Profiler id={`timeline-probe-${threadId}`} onRender={onCommit}>
      <output data-testid={`timeline-probe-${threadId}`}>{signature}</output>
    </Profiler>
  );
}

/** Plan 能力夹具只暴露 Workbench 实际接入的命令。 */
function makePlanController(overrides: Partial<PlanController> = {}): PlanController {
  return {
    planModel: undefined,
    revisions: [],
    evidence: [],
    loading: false,
    error: undefined,
    busyAction: undefined,
    refresh: vi.fn(async () => undefined),
    createPlan: vi.fn(async () => true),
    saveDraft: vi.fn(async () => true),
    discardDraft: vi.fn(async () => true),
    propose: vi.fn(async () => true),
    finalizePlan: vi.fn(async () => true),
    pausePlan: vi.fn(async () => true),
    resumePlan: vi.fn(async () => true),
    stopPlan: vi.fn(async () => true),
    execute: vi.fn(async () => true),
    reject: vi.fn(async () => true),
    ...overrides,
  };
}

describe("WorkbenchHost capability activation", () => {
  /** AI Review 使用 root Thread 的 gateway/取消端口，发现修复必须进入普通用户 Turn。 */
  it("wires review capture, cancellation, and repair to the active root Thread", async () => {
    const aiReviewGateway = {} as ComponentProps<typeof WorkbenchHost>["adapters"]["aiReview"];
    mocks.submitReviewTurn.mockResolvedValue({
      accepted: true,
      turnId: "turn_fix",
      queued: false,
      threadRevision: 6,
    });
    mocks.cancelReviewTurn.mockResolvedValue(undefined);
    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          rootThreadId: "thr_root",
          adapters: { aiReview: aiReviewGateway } as ComponentProps<
            typeof WorkbenchHost
          >["adapters"],
        })}
      />,
    );

    const reviewOptions = mocks.useAiReviewController.mock.calls.at(-1)?.[0] as {
      active: boolean;
      workspaceId: string;
      threadId: string;
      gateway: unknown;
      cancelTurn: (turnId: string) => Promise<void>;
    };
    expect(reviewOptions).toMatchObject({
      active: true,
      workspaceId: "ws_demo",
      threadId: "thr_root",
      gateway: aiReviewGateway,
    });

    const workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as {
      views: {
        review: {
          props: {
            aiReview: unknown;
            onRepairFinding: (
              prompt: string,
              finding: unknown,
              report: unknown,
            ) => Promise<{ turnId: string; queued: boolean }>;
          };
        };
      };
    };
    expect(workbenchProps.views.review.props.aiReview).toBe(
      mocks.useAiReviewController.mock.results.at(-1)?.value,
    );

    await act(async () => {
      await reviewOptions.cancelTurn("turn_review");
      await workbenchProps.views.review.props.onRepairFinding("repair prompt", {}, {});
    });
    expect(mocks.cancelReviewTurn).toHaveBeenCalledWith({ turnId: "turn_review" });
    expect(mocks.submitReviewTurn).toHaveBeenCalledWith({
      threadId: "thr_root",
      content: [{ type: "text", text: "repair prompt" }],
    });
  });

  /** 预览 owner 按授权 Thread 清理；只有与 Store 当前 SID/generation 相符的关闭通知可被接受。@author kongweiguang */
  it("routes a task close event to the matching retained attachment preview", async () => {
    const runtimeFence = establishReadyTimelineRuntime();
    const closeForThreads = vi.fn(async () => undefined);
    const target = {
      attachmentId: "att_closed",
      displayName: "closed.txt",
      mediaKind: "text" as const,
      authorization: { kind: "thread" as const, threadId: "thr_closed_child" },
    };
    mocks.usePreviewController.mockReturnValue({
      viewModel: {},
      actions: {
        attachment: { dismiss: vi.fn(), closeForThreads },
        openTarget: mocks.previewOpenTarget,
      },
    });
    render(
      <WorkbenchHost
        {...makeProps({ rootThreadId: "thr_other_root", attachmentTarget: target })}
      />,
    );

    const staleCloseEvent = {
      method: "task/closed",
      params: {
        serverInstanceId: "srv_stale_workbench_test",
        generation: runtimeFence.generation,
        eventId: "evt_closed_side_stale",
        sequence: 4,
        occurredAt: "2026-10-08T00:00:00Z",
        rootThreadId: "thr_owner_root",
        taskThreadId: "thr_closed_side_root",
        closedThreadIds: ["thr_closed_side_root", "thr_closed_child"],
      },
    } as unknown as Parameters<typeof publishTaskHostEvent>[0];
    const currentCloseEvent = {
      method: "task/closed",
      params: {
        ...runtimeFence,
        eventId: "evt_closed_side_current",
        sequence: 5,
        occurredAt: "2026-10-08T00:00:01Z",
        rootThreadId: "thr_owner_root",
        taskThreadId: "thr_closed_side_root",
        closedThreadIds: ["thr_closed_side_root", "thr_closed_child"],
      },
    } as unknown as Parameters<typeof publishTaskHostEvent>[0];
    act(() => publishTaskHostEvent(staleCloseEvent));
    expect(closeForThreads).not.toHaveBeenCalled();

    act(() => publishTaskHostEvent(currentCloseEvent));

    await waitFor(() =>
      expect(closeForThreads).toHaveBeenCalledExactlyOnceWith([
        "thr_closed_side_root",
        "thr_closed_child",
      ]),
    );
  });

  /** 同 root 关闭由 Task controller 统一处理；ACK 必须携带请求所属的 ready Runtime fence。@author kongweiguang */
  it("leaves a closed selected task and avoids duplicate same-root preview cleanup", async () => {
    const runtimeFence = establishReadyTimelineRuntime();
    const closeForThreads = vi.fn(async () => undefined);
    const onTabChange = vi.fn();
    const closedThreadIds = ["thr_closed_side"];
    mocks.usePreviewController.mockReturnValue({
      viewModel: {},
      actions: {
        attachment: { dismiss: vi.fn(), closeForThreads },
        openTarget: mocks.previewOpenTarget,
      },
    });
    render(
      <WorkbenchHost
        {...makeProps({
          rootThreadId: "thr_root",
          selectedTab: "side-task:thr_closed_side",
          onTabChange,
          attachmentTarget: {
            attachmentId: "att_closed",
            displayName: "closed.txt",
            mediaKind: "text",
            authorization: { kind: "thread", threadId: "thr_closed_side" },
          },
        })}
      />,
    );
    const taskControllerOptions = mocks.useTaskController.mock.calls.at(-1)?.[0] as
      | {
          onSideTaskClosed?: (
            threadIds: readonly string[],
            fence: TimelineRuntimeFence,
          ) => Promise<boolean>;
        }
      | undefined;

    await act(async () => {
      expect(await taskControllerOptions?.onSideTaskClosed?.(closedThreadIds, runtimeFence)).toBe(
        true,
      );
    });

    expect(onTabChange).toHaveBeenCalledExactlyOnceWith("agents");
    expect(closeForThreads).toHaveBeenCalledExactlyOnceWith(closedThreadIds);
    act(() =>
      publishTaskHostEvent({
        method: "task/closed",
        params: {
          ...runtimeFence,
          eventId: "evt_closed_side_duplicate",
          sequence: 6,
          occurredAt: "2026-10-08T00:00:02Z",
          rootThreadId: "thr_root",
          taskThreadId: "thr_closed_side",
          closedThreadIds,
        },
      } as unknown as Parameters<typeof publishTaskHostEvent>[0]),
    );
    expect(closeForThreads).toHaveBeenCalledOnce();
  });

  /** Preview 复制能力必须接收 Host 的统一 clipboard action，确保 Browser 菜单不会缺失入口。 */
  it("passes the host copy action into the Preview view", () => {
    const onCopyText = vi.fn(async () => undefined);
    render(<WorkbenchHost {...makeProps({ active: true, onCopyText })} />);

    const workbenchProps = mocks.Workbench.mock.calls.at(-1)?.[0] as
      | { views: { preview: { props: Record<string, unknown> } } }
      | undefined;
    expect(workbenchProps?.views.preview.props["onCopyText"]).toBe(onCopyText);
  });

  it("routes an explicitly clicked web destination through the Preview controller", async () => {
    const settled = vi.fn();
    const request = {
      requestId: 301,
      workspaceId: "ws_demo",
      threadId: "thr_one",
      target: { kind: "url" as const, url: "https://example.test/" },
    };

    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          rootThreadId: "thr_one",
          openTargetRequest: request,
          onOpenTargetSettled: settled,
        })}
      />,
    );

    await waitFor(() =>
      expect(mocks.previewOpenTarget).toHaveBeenCalledWith({
        kind: "url",
        url: "https://example.test/",
      }),
    );
    expect(settled).toHaveBeenCalledWith(301, "opened");
  });

  it("resolves clicked external text only on demand and opens it in a read-only Files tab", async () => {
    const resolveFile = vi.fn(async () => ({
      canonicalPath: "C:\\outside\\notes.txt",
      displayName: "notes.txt",
      workspaceId: null,
      workspaceRelativePath: null,
      withinWorkspace: false,
      kind: "text" as const,
      mimeType: "text/plain",
      fileUrl: "file:///C:/outside/notes.txt",
      content: "external text",
      truncated: false,
      line: 7,
      column: 2,
      readOnly: true,
    }));
    const openExternalDocument = vi.fn(() => true);
    const settled = vi.fn();
    const request = {
      requestId: 302,
      workspaceId: "ws_demo",
      threadId: "thr_one",
      target: { kind: "file" as const, path: "C:\\outside\\notes.txt", line: 7, column: 2 },
    };

    mocks.useFilesController.mockReturnValue({
      viewModel: { documents: {}, openPaths: [] },
      actions: {
        selectNode: vi.fn(),
        toggleDirectory: vi.fn(),
        openPath: vi.fn(async () => true),
        openExternalDocument,
      },
    });
    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          rootThreadId: "thr_one",
          adapters: { preview: { resolveFile } } as unknown as ComponentProps<
            typeof WorkbenchHost
          >["adapters"],
          openTargetRequest: request,
          onOpenTargetSettled: settled,
        })}
      />,
    );

    await waitFor(() => expect(openExternalDocument).toHaveBeenCalledOnce());
    expect(resolveFile).toHaveBeenCalledWith("C:\\outside\\notes.txt", "ws_demo", 7, 2, "editor");
    expect(openExternalDocument).toHaveBeenCalledWith({
      path: "C:\\outside\\notes.txt",
      content: "external text",
      line: 7,
      column: 2,
      truncated: false,
      readOnlyReason: "工作区外文件，只读",
    });
    expect(mocks.previewOpenTarget).not.toHaveBeenCalled();
    expect(settled).toHaveBeenCalledWith(302, "opened");
  });

  /** 正文不可读与命中读取上限必须给出不同原因，并保留可用的 Explorer 恢复动作。@author kongweiguang */
  it.each([
    {
      truncated: false,
      reason: "Ja 无法读取此文本文件正文，可能是文件权限或编码不兼容。",
      requestId: 320,
    },
    {
      truncated: true,
      reason: "文件超过 Ja 的读取上限，无法在 Ja 中显示正文。",
      requestId: 321,
    },
  ])("无法读取正文时说明 $reason", async ({ truncated, reason, requestId }) => {
    const revealFile = vi.fn(async () => undefined);
    const resolveFile = vi.fn(async () => ({
      canonicalPath: "C:\\outside\\large-notes.txt",
      displayName: "large-notes.txt",
      workspaceId: null,
      workspaceRelativePath: null,
      withinWorkspace: false,
      kind: "text" as const,
      mimeType: "text/plain",
      fileUrl: null,
      content: null,
      truncated,
      line: null,
      column: null,
      readOnly: true,
    }));
    const openExternalDocument = vi.fn(() => true);
    const onShowCapability = vi.fn();
    const onTabChange = vi.fn();
    const settled = vi.fn();
    mocks.useFilesController.mockReturnValue({
      viewModel: { documents: {}, openPaths: [] },
      actions: {
        selectNode: vi.fn(),
        toggleDirectory: vi.fn(),
        openPath: vi.fn(async () => true),
        openExternalDocument,
      },
    });

    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          rootThreadId: "thr_one",
          onShowCapability,
          onTabChange,
          adapters: { preview: { resolveFile, revealFile } } as unknown as ComponentProps<
            typeof WorkbenchHost
          >["adapters"],
          openTargetRequest: {
            requestId,
            workspaceId: "ws_demo",
            threadId: "thr_one",
            target: { kind: "file", path: "C:\\outside\\large-notes.txt" },
          },
          onOpenTargetSettled: settled,
        })}
      />,
    );

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith(reason, expect.any(Object)));
    expect(openExternalDocument).not.toHaveBeenCalled();
    expect(mocks.previewOpenTarget).not.toHaveBeenCalled();
    expect(onShowCapability).not.toHaveBeenCalled();
    expect(onTabChange).not.toHaveBeenCalled();
    expect(settled).toHaveBeenCalledWith(requestId, "failed");
    expect(revealFile).not.toHaveBeenCalled();

    const action = (
      vi.mocked(toast.error).mock.calls.at(-1)?.[1] as
        | { action?: { label: string; onClick: () => void } }
        | undefined
    )?.action;
    expect(action?.label).toBe("在文件资源管理器中打开");
    action?.onClick();
    await waitFor(() =>
      expect(revealFile).toHaveBeenCalledWith("C:\\outside\\large-notes.txt", "ws_demo"),
    );
  });

  /** Files Markdown 的只读引用使用相同原因与 Explorer 恢复动作，并避免打开空正文 Tab。@author kongweiguang */
  it("offers Explorer when a Markdown-linked external text file has no readable body", async () => {
    const revealFile = vi.fn(async () => undefined);
    const resolveFile = vi.fn(async () => ({
      canonicalPath: "C:\\outside\\reference.txt",
      displayName: "reference.txt",
      workspaceId: null,
      workspaceRelativePath: null,
      withinWorkspace: false,
      kind: "text" as const,
      mimeType: "text/plain",
      fileUrl: null,
      content: null,
      truncated: false,
      line: 9,
      column: null,
      readOnly: true,
    }));
    const openExternalDocument = vi.fn(() => true);
    const onShowCapability = vi.fn();
    mocks.useFilesController.mockReturnValue({
      viewModel: { documents: {}, openPaths: [] },
      actions: {
        selectNode: vi.fn(),
        toggleDirectory: vi.fn(),
        openPath: vi.fn(async () => true),
        openExternalDocument,
      },
    });
    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          rootThreadId: "thr_one",
          onShowCapability,
          adapters: { preview: { resolveFile, revealFile } } as unknown as ComponentProps<
            typeof WorkbenchHost
          >["adapters"],
        })}
      />,
    );

    const workbenchProps = mocks.Workbench.mock.calls.at(-1)?.[0] as
      | {
          views: {
            files: {
              props?: {
                onOpenMarkdownFile?: (
                  target: { path: string; line?: number; column?: number },
                  source: HTMLElement,
                  mode?: "explorer" | "browser",
                ) => Promise<void>;
              };
            };
          };
        }
      | undefined;
    await act(async () =>
      workbenchProps?.views.files.props?.onOpenMarkdownFile?.(
        { path: "C:\\outside\\reference.txt", line: 9 },
        document.createElement("a"),
      ),
    );

    expect(toast.error).toHaveBeenCalledWith(
      "Ja 无法读取此文本文件正文，可能是文件权限或编码不兼容。",
      expect.objectContaining({ action: expect.any(Object) }),
    );
    expect(openExternalDocument).not.toHaveBeenCalled();
    expect(onShowCapability).not.toHaveBeenCalled();
    const action = (
      vi.mocked(toast.error).mock.calls.at(-1)?.[1] as
        | { action?: { label: string; onClick: () => void } }
        | undefined
    )?.action;
    expect(action?.label).toBe("在文件资源管理器中打开");
    action?.onClick();
    await waitFor(() =>
      expect(revealFile).toHaveBeenCalledWith("C:\\outside\\reference.txt", "ws_demo"),
    );
  });

  /** 超限但仍有安全片段时保留只读工作面，同时提供打开原文件的恢复动作。@author kongweiguang */
  it("keeps a truncated text snapshot and offers Explorer for the complete file", async () => {
    const revealFile = vi.fn(async () => undefined);
    const resolveFile = vi.fn(async () => ({
      canonicalPath: "C:\\outside\\large-notes.txt",
      displayName: "large-notes.txt",
      workspaceId: null,
      workspaceRelativePath: null,
      withinWorkspace: false,
      kind: "text" as const,
      mimeType: "text/plain",
      fileUrl: "file:///C:/outside/large-notes.txt",
      content: "safe excerpt",
      truncated: true,
      line: null,
      column: null,
      readOnly: true,
    }));
    const openExternalDocument = vi.fn(() => true);
    const onShowCapability = vi.fn();
    mocks.useFilesController.mockReturnValue({
      viewModel: { documents: {}, openPaths: [] },
      actions: {
        selectNode: vi.fn(),
        toggleDirectory: vi.fn(),
        openPath: vi.fn(async () => true),
        openExternalDocument,
      },
    });

    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          rootThreadId: "thr_one",
          onShowCapability,
          adapters: { preview: { resolveFile, revealFile } } as unknown as ComponentProps<
            typeof WorkbenchHost
          >["adapters"],
          openTargetRequest: {
            requestId: 322,
            workspaceId: "ws_demo",
            threadId: "thr_one",
            target: { kind: "file", path: "C:\\outside\\large-notes.txt" },
          },
        })}
      />,
    );

    await waitFor(() => expect(openExternalDocument).toHaveBeenCalledOnce());
    expect(openExternalDocument).toHaveBeenCalledWith(
      expect.objectContaining({
        path: "C:\\outside\\large-notes.txt",
        content: "safe excerpt",
        truncated: true,
        readOnlyReason: "只读快照，内容已截断",
      }),
    );
    expect(onShowCapability).toHaveBeenCalledWith("files");
    expect(toast.info).toHaveBeenCalledWith(
      "文件超过 Ja 的读取上限，目前只显示部分内容。",
      expect.objectContaining({ action: expect.any(Object) }),
    );
    const action = (
      vi.mocked(toast.info).mock.calls.at(-1)?.[1] as
        | { action?: { label: string; onClick: () => void } }
        | undefined
    )?.action;
    expect(action?.label).toBe("在文件资源管理器中打开");
    action?.onClick();
    await waitFor(() =>
      expect(revealFile).toHaveBeenCalledWith("C:\\outside\\large-notes.txt", "ws_demo"),
    );
  });

  /** 项目内文件继续由 4 MiB Files reader 判定；Preview 的 1 MiB 摘要不能短路真实读取。@author kongweiguang */
  it("offers Explorer when the in-workspace Files reader reports its own size limit", async () => {
    const fileSize = 5 * 1024 * 1024;
    const revealFile = vi.fn(async () => undefined);
    const resolveFile = vi.fn(async () => ({
      canonicalPath: "C:\\repo\\src\\large.txt",
      displayName: "large.txt",
      workspaceId: "ws_demo",
      workspaceRelativePath: "src/large.txt",
      withinWorkspace: true,
      kind: "text" as const,
      mimeType: "text/plain",
      fileUrl: null,
      content: "bounded Preview prefix",
      truncated: true,
      line: null,
      column: null,
      readOnly: false,
    }));
    const readFile = mocks.filesRead;
    readFile.mockResolvedValue({
      path: "src/large.txt",
      kind: "too_large",
      content: null,
      revision: {
        kind: "file",
        size: fileSize,
        modifiedUnixMillis: null,
        sha256: null,
      },
      size: fileSize,
      encoding: null,
      newline: "unknown",
    });
    const openPath = vi.fn(async () => true);
    const onShowCapability = vi.fn();
    mocks.useFilesController.mockReturnValue({
      viewModel: { documents: {}, openPaths: [] },
      actions: {
        selectNode: vi.fn(),
        toggleDirectory: vi.fn(),
        openPath,
        openExternalDocument: vi.fn(() => true),
      },
    });

    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          rootThreadId: "thr_one",
          onShowCapability,
          adapters: {
            preview: { resolveFile, revealFile },
          } as unknown as ComponentProps<typeof WorkbenchHost>["adapters"],
          openTargetRequest: {
            requestId: 324,
            workspaceId: "ws_demo",
            threadId: "thr_one",
            target: { kind: "file", path: "src/large.txt" },
          },
        })}
      />,
    );

    await waitFor(() => expect(resolveFile).toHaveBeenCalledOnce());
    await waitFor(() => expect(openPath).toHaveBeenCalledWith("src/large.txt", undefined));
    const controllerArgs = mocks.useFilesController.mock.calls.at(-1)?.[0] as
      | {
          operations: {
            readFile: (input: { workspaceId: string; relativePath: string }) => Promise<unknown>;
          };
        }
      | undefined;
    expect(controllerArgs?.operations.readFile).toBeTypeOf("function");
    await act(async () => {
      await controllerArgs?.operations.readFile({
        workspaceId: "ws_demo",
        relativePath: "src/large.txt",
      });
    });
    await waitFor(() =>
      expect(readFile).toHaveBeenCalledWith({
        workspaceId: "ws_demo",
        relativePath: "src/large.txt",
      }),
    );
    expect(onShowCapability).toHaveBeenCalledWith("files");
    expect(mocks.previewOpenTarget).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith(
      "文件超过 Ja 文件编辑器的读取上限，无法显示正文。",
      expect.objectContaining({ action: expect.any(Object) }),
    );

    const action = (
      vi.mocked(toast.error).mock.calls.at(-1)?.[1] as
        | { action?: { label: string; onClick: () => void } }
        | undefined
    )?.action;
    expect(action?.label).toBe("在文件资源管理器中打开");
    action?.onClick();
    await waitFor(() => expect(revealFile).toHaveBeenCalledWith("src/large.txt", "ws_demo"));
  });

  /** 不支持的格式不切换工作面；Explorer 动作保留用户继续处理原文件的路径。@author kongweiguang */
  it("offers Explorer for an unsupported file without switching the current work surface", async () => {
    const revealFile = vi.fn(async () => undefined);
    const resolveFile = vi.fn(async () => ({
      canonicalPath: "C:\\outside\\archive.bin",
      displayName: "archive.bin",
      workspaceId: null,
      workspaceRelativePath: null,
      withinWorkspace: false,
      kind: "unsupported" as const,
      mimeType: "application/octet-stream",
      fileUrl: null,
      content: null,
      truncated: false,
      line: null,
      column: null,
      readOnly: true,
    }));
    const onShowCapability = vi.fn();
    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          rootThreadId: "thr_one",
          onShowCapability,
          adapters: { preview: { resolveFile, revealFile } } as unknown as ComponentProps<
            typeof WorkbenchHost
          >["adapters"],
          openTargetRequest: {
            requestId: 323,
            workspaceId: "ws_demo",
            threadId: "thr_one",
            target: { kind: "file", path: "C:\\outside\\archive.bin" },
          },
        })}
      />,
    );

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith(
        "此文件类型暂不支持在 Ja 中打开。",
        expect.objectContaining({ action: expect.any(Object) }),
      ),
    );
    expect(onShowCapability).not.toHaveBeenCalled();
    expect(revealFile).not.toHaveBeenCalled();
    const action = (
      vi.mocked(toast.error).mock.calls.at(-1)?.[1] as
        | { action?: { label: string; onClick: () => void } }
        | undefined
    )?.action;
    expect(action?.label).toBe("在资源管理器中打开");
    action?.onClick();
    await waitFor(() =>
      expect(revealFile).toHaveBeenCalledWith("C:\\outside\\archive.bin", "ws_demo"),
    );
  });

  /** 右栏隐藏时仅允许显式 Explorer intent 运行，避免无关 Preview/Files 激活。 */
  it("reveals an external file without opening a Workbench tab or decoding text", async () => {
    const revealFile = vi.fn(async () => undefined);
    const resolveFile = vi.fn();
    const settled = vi.fn();
    const onTabChange = vi.fn();
    render(
      <WorkbenchHost
        {...makeProps({
          active: false,
          rootThreadId: "thr_one",
          onTabChange,
          adapters: { preview: { revealFile, resolveFile } } as unknown as ComponentProps<
            typeof WorkbenchHost
          >["adapters"],
          openTargetRequest: {
            requestId: 306,
            workspaceId: "ws_demo",
            threadId: "thr_one",
            target: { kind: "explorer", path: "C:\\outside\\资料 空间\\图.svg" },
          },
          onOpenTargetSettled: settled,
        })}
      />,
    );

    await waitFor(() =>
      expect(revealFile).toHaveBeenCalledWith("C:\\outside\\资料 空间\\图.svg", "ws_demo"),
    );
    expect(resolveFile).not.toHaveBeenCalled();
    expect(mocks.previewOpenTarget).not.toHaveBeenCalled();
    expect(onTabChange).not.toHaveBeenCalled();
    expect(settled).toHaveBeenCalledWith(306, "opened");
  });

  /** 项目外目录的普通打开保留在当前项目，并在用户确认后才调用 Explorer。 */
  it("offers Explorer for an external directory without opening it automatically", async () => {
    const revealFile = vi.fn(async () => undefined);
    const resolveFile = vi.fn(async () => ({
      canonicalPath: "C:\\outside\\资料",
      displayName: "资料",
      workspaceId: "ws_demo",
      workspaceRelativePath: null,
      withinWorkspace: false,
      kind: "directory" as const,
      mimeType: null,
      fileUrl: null,
      content: null,
      truncated: false,
      line: null,
      column: null,
      readOnly: true,
    }));
    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          rootThreadId: "thr_one",
          adapters: { preview: { resolveFile, revealFile } } as unknown as ComponentProps<
            typeof WorkbenchHost
          >["adapters"],
          openTargetRequest: {
            requestId: 307,
            workspaceId: "ws_demo",
            threadId: "thr_one",
            target: { kind: "file", path: "C:\\outside\\资料" },
          },
        })}
      />,
    );

    await waitFor(() => expect(toast.info).toHaveBeenCalledOnce());
    expect(revealFile).not.toHaveBeenCalled();
    const action = vi.mocked(toast.info).mock.calls[0]?.[1]?.action as
      | { onClick: () => void }
      | undefined;
    expect(action).toBeDefined();
    action?.onClick();
    await waitFor(() => expect(revealFile).toHaveBeenCalledWith("C:\\outside\\资料", "ws_demo"));
  });

  it("passes a canonical browser file path and reveal position to the current Preview page", async () => {
    const resolveFile = vi.fn(async () => ({
      canonicalPath: "C:\\repo\\assets\\map.svg",
      displayName: "map.svg",
      workspaceId: "ws_demo",
      workspaceRelativePath: "assets/map.svg",
      withinWorkspace: true,
      kind: "browser" as const,
      mimeType: "image/svg+xml",
      fileUrl: "file:///C:/repo/assets/map.svg",
      content: null,
      truncated: false,
      line: 12,
      column: 3,
      readOnly: true,
    }));
    const request = {
      requestId: 304,
      workspaceId: "ws_demo",
      threadId: "thr_one",
      target: { kind: "file" as const, path: "assets/map.svg", line: 12, column: 3 },
    };

    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          rootThreadId: "thr_one",
          adapters: { preview: { resolveFile } } as unknown as ComponentProps<
            typeof WorkbenchHost
          >["adapters"],
          openTargetRequest: request,
        })}
      />,
    );

    await waitFor(() =>
      expect(mocks.previewOpenTarget).toHaveBeenCalledWith({
        kind: "file",
        path: "C:\\repo\\assets\\map.svg",
        previewMode: "editor",
        line: 12,
        column: 3,
      }),
    );
    expect(resolveFile).toHaveBeenCalledWith("assets/map.svg", "ws_demo", 12, 3, "editor");
  });

  it("opens a resolved workspace text file in its writable relative Files tab", async () => {
    const resolveFile = vi.fn(async () => ({
      canonicalPath: "C:\\repo\\src\\main.ts",
      displayName: "main.ts",
      workspaceId: "ws_demo",
      workspaceRelativePath: "src/main.ts",
      withinWorkspace: true,
      kind: "text" as const,
      mimeType: "text/plain",
      fileUrl: "file:///C:/repo/src/main.ts",
      content: null,
      truncated: false,
      line: 7,
      column: 2,
      readOnly: false,
    }));
    const openPath = vi.fn(async () => true);
    const request = {
      requestId: 305,
      workspaceId: "ws_demo",
      threadId: "thr_one",
      target: { kind: "file" as const, path: "src/main.ts", line: 7, column: 2 },
    };
    mocks.useFilesController.mockReturnValue({
      viewModel: { documents: {}, openPaths: [] },
      actions: {
        selectNode: vi.fn(),
        toggleDirectory: vi.fn(),
        openPath,
        openExternalDocument: vi.fn(() => true),
      },
    });

    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          rootThreadId: "thr_one",
          adapters: { preview: { resolveFile } } as unknown as ComponentProps<
            typeof WorkbenchHost
          >["adapters"],
          openTargetRequest: request,
        })}
      />,
    );

    await waitFor(() =>
      expect(openPath).toHaveBeenCalledWith("src/main.ts", { line: 7, column: 2 }),
    );
    expect(mocks.previewOpenTarget).not.toHaveBeenCalled();
  });

  /** 根目录文件名也是精确相对路径；其它目录同名文件不得触发搜索或改写目标。 */
  it.each(["notes.txt", "b/notes.txt", "C:/repo/b/notes.txt"])(
    "opens exact file target %s without searching duplicate basenames",
    async (path) => {
      const resolveFile = vi.fn(async () => ({
        canonicalPath: "C:\\repo\\b\\notes.txt",
        displayName: "notes.txt",
        workspaceId: "ws_demo",
        workspaceRelativePath: "b/notes.txt",
        withinWorkspace: true,
        kind: "text" as const,
        mimeType: "text/plain",
        fileUrl: "file:///C:/repo/b/notes.txt",
        content: null,
        truncated: false,
        line: null,
        column: null,
        readOnly: false,
      }));
      const request = {
        requestId: 303,
        workspaceId: "ws_demo",
        threadId: "thr_one",
        target: { kind: "file" as const, path },
      };
      mocks.queryRuntime.mockResolvedValue({
        items: [
          { relativePath: "a/notes.txt", kind: "file" },
          { relativePath: "b/notes.txt", kind: "file" },
        ],
        truncated: false,
      });

      render(
        <WorkbenchHost
          {...makeProps({
            active: true,
            rootThreadId: "thr_one",
            adapters: { preview: { resolveFile } } as unknown as ComponentProps<
              typeof WorkbenchHost
            >["adapters"],
            openTargetRequest: request,
          })}
        />,
      );

      await waitFor(() =>
        expect(resolveFile).toHaveBeenCalledWith(path, "ws_demo", undefined, undefined, "editor"),
      );
      expect(mocks.queryRuntime).not.toHaveBeenCalledWith(
        "workspace/path/search",
        expect.anything(),
      );
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    },
  );

  /** Explorer 同样保留回复的绝对目标，不在隐藏右栏时查询或弹出同名选择。 */
  it("reveals an exact Explorer target while the right panel stays hidden", async () => {
    const revealFile = vi.fn(async () => undefined);
    const settled = vi.fn();
    const onTabChange = vi.fn();
    const path = "C:/repo/b/notes.txt";
    render(
      <WorkbenchHost
        {...makeProps({
          active: false,
          rootThreadId: "thr_one",
          onTabChange,
          adapters: { preview: { revealFile } } as unknown as ComponentProps<
            typeof WorkbenchHost
          >["adapters"],
          openTargetRequest: {
            requestId: 307,
            workspaceId: "ws_demo",
            threadId: "thr_one",
            target: { kind: "explorer", path },
          },
          onOpenTargetSettled: settled,
        })}
      />,
    );

    await waitFor(() => expect(revealFile).toHaveBeenCalledWith(path, "ws_demo"));
    expect(settled).toHaveBeenCalledWith(307, "opened");
    expect(mocks.queryRuntime).not.toHaveBeenCalledWith("workspace/path/search", expect.anything());
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(onTabChange).not.toHaveBeenCalled();
  });

  /** 路径缺失只能报失败，不能搜索同名文件代替，避免打开错误内容。 */
  it("does not replace a missing exact file with another basename match", async () => {
    const resolveFile = vi.fn(async () => {
      throw new PreviewAdapterError("file_not_found");
    });
    const settled = vi.fn();
    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          rootThreadId: "thr_one",
          adapters: { preview: { resolveFile } } as unknown as ComponentProps<
            typeof WorkbenchHost
          >["adapters"],
          openTargetRequest: {
            requestId: 309,
            workspaceId: "ws_demo",
            threadId: "thr_one",
            target: { kind: "file", path: "notes.txt" },
          },
          onOpenTargetSettled: settled,
        })}
      />,
    );
    await waitFor(() => expect(settled).toHaveBeenCalledWith(309, "failed"));
    expect(resolveFile).toHaveBeenCalledExactlyOnceWith(
      "notes.txt",
      "ws_demo",
      undefined,
      undefined,
      "editor",
    );
    expect(mocks.queryRuntime).not.toHaveBeenCalledWith("workspace/path/search", expect.anything());
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mocks.previewOpenTarget).not.toHaveBeenCalled();
  });

  /** 原生拒绝必须结束当前 intent，供应用层把焦点还给回复中的路径。 */
  it("settles an Explorer reveal error without activating the right panel", async () => {
    const settled = vi.fn();
    const onTabChange = vi.fn();
    render(
      <WorkbenchHost
        {...makeProps({
          active: false,
          rootThreadId: "thr_one",
          onTabChange,
          adapters: {
            preview: {
              revealFile: vi.fn(async () => {
                throw new PreviewAdapterError("file_not_found");
              }),
            },
          } as unknown as ComponentProps<typeof WorkbenchHost>["adapters"],
          openTargetRequest: {
            requestId: 308,
            workspaceId: "ws_demo",
            threadId: "thr_one",
            target: { kind: "explorer", path: "C:\\outside\\missing.txt" },
          },
          onOpenTargetSettled: settled,
        })}
      />,
    );
    await waitFor(() => expect(settled).toHaveBeenCalledWith(308, "failed"));
    expect(onTabChange).not.toHaveBeenCalled();
  });

  /** 菜单仅遮挡 child WebView；取消菜单恢复可见性，不得销毁网页或改变活动页签。 */
  it("temporarily hides the native preview while the tab context menu is open", () => {
    const props = makeProps({ active: true, selectedTab: "preview", openTabs: ["preview"] });
    render(<WorkbenchHost {...props} />);
    const menuOpenChange = (
      mocks.Workbench.mock.calls.at(-1)?.[0] as {
        onTabContextMenuOpenChange: (open: boolean) => void;
      }
    ).onTabContextMenuOpenChange;
    expect(mocks.usePreviewController.mock.calls.at(-1)?.[0].active).toBe(true);
    act(() => menuOpenChange(true));
    expect(mocks.usePreviewController.mock.calls.at(-1)?.[0].active).toBe(false);
    act(() => menuOpenChange(false));
    expect(mocks.usePreviewController.mock.calls.at(-1)?.[0].active).toBe(true);
    expect(mocks.useJaWorkbench.mock.results.at(-1)?.value.closePreview).not.toHaveBeenCalled();
    expect(props.onTabChange).not.toHaveBeenCalled();
  });

  /** Turn Review 沿用 Inspector 与 Tab 双可见性，隐藏挂载不能继续打开或读取预览。 */
  it("只在 Inspector 可见且选中 Review 时激活本轮修改视图", () => {
    const turnReviewTarget: NonNullable<ComponentProps<typeof WorkbenchHost>["turnReviewTarget"]> =
      {
        kind: "frozen_turn",
        workspaceId: "ws_demo",
        threadId: "thr_one",
        turnId: "turn_latest",
        artifactId: "artifact_latest",
        threadRevision: 2,
        state: "complete",
        incompleteReasons: [],
        files: [],
        stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
      };
    const { rerender } = render(<WorkbenchHost {...makeProps({ turnReviewTarget })} />);
    let workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      views: { review: { props: { active: boolean } } };
    };
    expect(workbenchProps.views.review.props.active).toBe(false);

    rerender(
      <WorkbenchHost
        {...makeProps({
          active: true,
          selectedTab: "files",
          openTabs: ["review", "files"],
          turnReviewTarget,
        })}
      />,
    );
    workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      views: { review: { props: { active: boolean } } };
    };
    expect(workbenchProps.views.review.props.active).toBe(false);

    rerender(<WorkbenchHost {...makeProps({ active: true, turnReviewTarget })} />);
    workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      views: { review: { props: { active: boolean } } };
    };
    expect(workbenchProps.views.review.props.active).toBe(true);
    expect(mocks.useReviewController).toHaveBeenLastCalledWith(
      expect.objectContaining({ catalogEnabled: true, snapshotEnabled: false }),
    );
  });

  it("只有可见、已打开且选中的 Review 才启用重型 snapshot", () => {
    const { rerender } = render(<WorkbenchHost {...makeProps()} />);
    expect(mocks.useReviewController).toHaveBeenLastCalledWith(
      expect.objectContaining({ snapshotEnabled: false }),
    );

    rerender(<WorkbenchHost {...makeProps({ active: true, selectedTab: "files" })} />);
    expect(mocks.useReviewController).toHaveBeenLastCalledWith(
      expect.objectContaining({ snapshotEnabled: false }),
    );

    rerender(<WorkbenchHost {...makeProps({ active: true, openTabs: ["files"] })} />);
    expect(mocks.useReviewController).toHaveBeenLastCalledWith(
      expect.objectContaining({ snapshotEnabled: false }),
    );

    rerender(<WorkbenchHost {...makeProps({ active: true })} />);
    expect(mocks.useReviewController).toHaveBeenLastCalledWith(
      expect.objectContaining({
        workspaceId: "ws_demo",
        generation: 7,
        snapshotEnabled: true,
      }),
    );

    rerender(
      <WorkbenchHost
        {...makeProps({
          active: true,
          turnReviewTarget: {
            kind: "frozen_turn",
            workspaceId: "ws_demo",
            threadId: "thr_one",
            turnId: "turn_latest",
            artifactId: "artifact_latest",
            threadRevision: 2,
            state: "complete",
            incompleteReasons: [],
            files: [],
            stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
          },
        })}
      />,
    );
    expect(mocks.useReviewController).toHaveBeenLastCalledWith(
      expect.objectContaining({ snapshotEnabled: false }),
    );
  });

  it("在 Git 与 Turn 往返时按范围恢复有界导航提示", () => {
    const gitNavigation = {
      detailOpen: false,
      query: "src/",
      tree: { grouping: "status_directory", openedIds: ["src"], collapsedIds: [], scrollTop: 48 },
    } as const;
    const turnNavigation = {
      detailOpen: true,
      query: "live",
      selectedPath: "src/b.ts",
      consumedPathRequestRevision: 4,
    } as const;
    const turnReviewTarget: NonNullable<ComponentProps<typeof WorkbenchHost>["turnReviewTarget"]> =
      {
        kind: "frozen_turn",
        workspaceId: "ws_demo",
        threadId: "thr_one",
        turnId: "turn_one",
        artifactId: "artifact_one",
        threadRevision: 2,
        state: "complete",
        incompleteReasons: [],
        files: [],
        stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
      };
    const { rerender } = render(
      <WorkbenchHost {...makeProps({ active: true, rootThreadId: "thr_one" })} />,
    );
    let workbenchProps = mocks.Workbench.mock.calls.at(-1)?.[0] as unknown as {
      views: { review: { key: string | null; props: Record<string, unknown> } };
    };
    expect(workbenchProps.views.review.key).toBe("ws_demo:thr_one:git:uncommitted:all");
    const gitProps = workbenchProps.views.review.props as {
      onNavigationStateChange: (state: typeof gitNavigation) => void;
    };
    act(() => gitProps.onNavigationStateChange(gitNavigation));

    rerender(
      <WorkbenchHost {...makeProps({ active: true, rootThreadId: "thr_one", turnReviewTarget })} />,
    );
    workbenchProps = mocks.Workbench.mock.calls.at(-1)?.[0] as unknown as {
      views: { review: { key: string | null; props: Record<string, unknown> } };
    };
    expect(workbenchProps.views.review.key).toBe("ws_demo:turn:thr_one:turn_one");
    const turnProps = workbenchProps.views.review.props as {
      navigationState?: unknown;
      onNavigationStateChange: (state: typeof turnNavigation) => void;
    };
    expect(turnProps.navigationState).toBeUndefined();
    act(() => turnProps.onNavigationStateChange(turnNavigation));

    rerender(<WorkbenchHost {...makeProps({ active: true, rootThreadId: "thr_one" })} />);
    workbenchProps = mocks.Workbench.mock.calls.at(-1)?.[0] as unknown as {
      views: { review: { key: string | null; props: Record<string, unknown> } };
    };
    expect(workbenchProps.views.review.props).toEqual(
      expect.objectContaining({ navigationState: gitNavigation }),
    );

    rerender(
      <WorkbenchHost {...makeProps({ active: true, rootThreadId: "thr_one", turnReviewTarget })} />,
    );
    workbenchProps = mocks.Workbench.mock.calls.at(-1)?.[0] as unknown as {
      views: { review: { key: string | null; props: Record<string, unknown> } };
    };
    expect(workbenchProps.views.review.props).toEqual(
      expect.objectContaining({ navigationState: turnNavigation }),
    );
    rerender(<WorkbenchHost {...makeProps({ active: true, rootThreadId: "thr_one" })} />);

    mocks.useReviewController.mockReturnValue({
      viewModel: {
        state: { catalog: undefined, source: { kind: "staged" }, layerFilter: "all" },
        sourceOptions: [{ kind: "uncommitted" }, { kind: "staged" }],
      },
      actions: { setSource: vi.fn(), setLayerFilter: vi.fn() },
    });
    rerender(<WorkbenchHost {...makeProps({ active: true, rootThreadId: "thr_one" })} />);
    workbenchProps = mocks.Workbench.mock.calls.at(-1)?.[0] as unknown as {
      views: { review: { key: string | null; props: Record<string, unknown> } };
    };
    expect(workbenchProps.views.review.key).toBe("ws_demo:thr_one:git:staged:all");
    expect(workbenchProps.views.review.props["navigationState"]).toBeUndefined();

    mocks.useReviewController.mockReturnValue({
      viewModel: {
        state: { catalog: undefined, source: { kind: "uncommitted" }, layerFilter: "all" },
        sourceOptions: [{ kind: "uncommitted" }, { kind: "staged" }],
      },
      actions: { setSource: vi.fn(), setLayerFilter: vi.fn() },
    });
    rerender(<WorkbenchHost {...makeProps({ active: true, rootThreadId: "thr_one" })} />);
    workbenchProps = mocks.Workbench.mock.calls.at(-1)?.[0] as unknown as {
      views: { review: { key: string | null; props: Record<string, unknown> } };
    };
    expect(workbenchProps.views.review.key).toBe("ws_demo:thr_one:git:uncommitted:all");
    expect(workbenchProps.views.review.props).toEqual(
      expect.objectContaining({ navigationState: gitNavigation }),
    );

    rerender(<WorkbenchHost {...makeProps({ active: true, rootThreadId: "thr_two" })} />);
    workbenchProps = mocks.Workbench.mock.calls.at(-1)?.[0] as unknown as {
      views: { review: { key: string | null; props: Record<string, unknown> } };
    };
    expect(workbenchProps.views.review.props["navigationState"]).toBeUndefined();

    rerender(<WorkbenchHost {...makeProps({ active: true, rootThreadId: "thr_one" })} />);
    workbenchProps = mocks.Workbench.mock.calls.at(-1)?.[0] as unknown as {
      views: { review: { key: string | null; props: Record<string, unknown> } };
    };
    expect(workbenchProps.views.review.props).toEqual(
      expect.objectContaining({ navigationState: gitNavigation }),
    );
  });

  it("只有可见、已打开且选中的 Files 才启用 Tree 与 Watcher 活动", () => {
    const { rerender } = render(<WorkbenchHost {...makeProps()} />);
    expect(mocks.useFilesController).toHaveBeenLastCalledWith(
      expect.objectContaining({ activityEnabled: false }),
    );

    rerender(
      <WorkbenchHost
        {...makeProps({ active: true, selectedTab: "files", openTabs: ["review", "files"] })}
      />,
    );
    expect(mocks.useFilesController).toHaveBeenLastCalledWith(
      expect.objectContaining({ activityEnabled: true }),
    );

    rerender(
      <WorkbenchHost
        {...makeProps({ active: true, selectedTab: "review", openTabs: ["review", "files"] })}
      />,
    );
    expect(mocks.useFilesController).toHaveBeenLastCalledWith(
      expect.objectContaining({ activityEnabled: false }),
    );
  });

  it("把 Files 右键动作转换为当前 Workspace 的结构化对话引用", () => {
    const onAddWorkspaceReference = vi.fn();
    render(<WorkbenchHost {...makeProps({ onAddWorkspaceReference })} />);
    const workbenchProps = mocks.Workbench.mock.calls.at(-1)?.[0] as unknown as {
      views: {
        files: {
          props: {
            onAddToConversation: (node: { path: string; kind: "file" | "directory" }) => void;
          };
        };
      };
    };

    act(() =>
      workbenchProps.views.files.props.onAddToConversation({
        path: "src/main.ts",
        kind: "file",
      }),
    );
    expect(onAddWorkspaceReference).toHaveBeenCalledWith({
      type: "workspace_reference",
      workspaceId: "ws_demo",
      relativePath: "src/main.ts",
      kind: "file",
    });
  });

  /** Composer 引用必须复用唯一 Files controller，且只在 Files 激活后消费一次。 */
  it("把当前 Workspace 的文件引用交给 Files controller 打开", async () => {
    const selectNode = vi.fn();
    const onSettled = vi.fn();
    mocks.useFilesController.mockReturnValue({
      viewModel: { documents: {}, openPaths: [] },
      actions: { selectNode, toggleDirectory: vi.fn() },
    });
    const request = {
      requestId: 4,
      reference: {
        type: "workspace_reference" as const,
        workspaceId: "ws_demo",
        relativePath: "src/main.ts",
        kind: "file" as const,
      },
    };
    const view = render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          selectedTab: "files",
          openTabs: ["files"],
          workspaceReferencePreviewRequest: request,
          onWorkspaceReferencePreviewSettled: onSettled,
        })}
      />,
    );
    expect(selectNode).toHaveBeenCalledWith(
      expect.objectContaining({ path: "src/main.ts", kind: "file" }),
    );

    mocks.useFilesController.mockReturnValue({
      viewModel: {
        activePath: "src/main.ts",
        selectedPath: "src/main.ts",
        documents: { "src/main.ts": { path: "src/main.ts" } },
        openPaths: ["src/main.ts"],
      },
      actions: { selectNode, toggleDirectory: vi.fn() },
    });
    view.rerender(
      <WorkbenchHost
        {...makeProps({
          active: true,
          selectedTab: "files",
          openTabs: ["files"],
          workspaceReferencePreviewRequest: request,
          onWorkspaceReferencePreviewSettled: onSettled,
        })}
      />,
    );
    await waitFor(() => expect(onSettled).toHaveBeenCalledWith(4, "opened"));
    view.rerender(
      <WorkbenchHost
        {...makeProps({
          active: true,
          selectedTab: "files",
          openTabs: ["files"],
          workspaceReferencePreviewRequest: request,
          onWorkspaceReferencePreviewSettled: onSettled,
        })}
      />,
    );
    expect(onSettled.mock.calls.filter(([, outcome]) => outcome === "opened")).toHaveLength(1);

    mocks.useFilesController.mockReturnValue({
      viewModel: { documents: {}, openPaths: [] },
      actions: { selectNode, toggleDirectory: vi.fn() },
    });
    view.rerender(
      <WorkbenchHost
        {...makeProps({
          active: true,
          selectedTab: "files",
          openTabs: ["files"],
          workspaceReferencePreviewRequest: request,
          onWorkspaceReferencePreviewSettled: onSettled,
        })}
      />,
    );
    expect(onSettled).toHaveBeenCalledWith(4, "closed");
  });

  /** 错误 Workspace 与逃逸路径在 Files IO 前失败关闭，并以稳定结果交还壳层恢复焦点。 */
  it("拒绝失效 Workspace 引用而不调用 Files controller", () => {
    const selectNode = vi.fn();
    const onSettled = vi.fn();
    mocks.useFilesController.mockReturnValue({
      viewModel: { documents: {}, openPaths: [] },
      actions: { selectNode, toggleDirectory: vi.fn() },
    });
    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          selectedTab: "files",
          openTabs: ["files"],
          workspaceReferencePreviewRequest: {
            requestId: 8,
            reference: {
              type: "workspace_reference",
              workspaceId: "ws_other",
              relativePath: "../secret.txt",
              kind: "file",
            },
          },
          onWorkspaceReferencePreviewSettled: onSettled,
        })}
      />,
    );
    expect(selectNode).not.toHaveBeenCalled();
    expect(onSettled).toHaveBeenCalledWith(8, "failed");
  });

  it("首次 Subagent 只后台加入总览 Tab，不改变当前焦点", () => {
    const onTabChange = vi.fn();
    const onOpenTabsChange = vi.fn();
    render(
      <WorkbenchHost
        {...makeProps({
          selectedTab: "files",
          openTabs: ["files"],
          onTabChange,
          onOpenTabsChange,
        })}
      />,
    );
    const options = mocks.useTaskController.mock.calls.at(-1)?.[0] as {
      onSubagentDiscovered?: () => void;
    };
    act(() => options.onSubagentDiscovered?.());
    expect(onOpenTabsChange).toHaveBeenCalledWith(["files", "agents"]);
    expect(onTabChange).not.toHaveBeenCalled();
  });

  it("selected Subagent 的 snapshot adapter 驱动 Timeline 订阅，不创建 Composer", async () => {
    const subagentTask: TaskSummary = {
      taskThreadId: "thr_subagent",
      parentThreadId: "thr_root",
      rootThreadId: "thr_root",
      originTurnId: "turn_parent",
      taskName: "检查合同",
      depth: 1,
      taskKind: "subagent",
      lifecycle: "attached",
      state: "completed",
      revision: 12,
      latestActivitySequence: 4,
      unreadCount: 0,
      descendantCount: 0,
      runningDescendantCount: 0,
      needsAttentionCount: 0,
      latestSafeSummary: "已完成",
      startedAt: "2026-09-10T10:00:00Z",
      completedAt: "2026-09-10T10:00:05Z",
      updatedAt: "2026-09-10T10:00:05Z",
    };
    const subagentSnapshot: TimelineSnapshot = {
      threadId: subagentTask.taskThreadId,
      revision: 12,
      turns: [
        {
          origin: "native" as const,
          turnId: "turn_subagent",
          status: "completed",
          requestedAt: "2026-09-10T10:00:00Z",
          updatedAt: "2026-09-10T10:00:05Z",
          completedAt: "2026-09-10T10:00:05Z",
          errorCode: null,
          changeSet: null,
        },
      ],
      items: [
        {
          origin: "native" as const,
          itemId: "item_subagent_answer",
          createdAt: "2026-09-10T10:00:05Z",
          turnId: "turn_subagent",
          truncated: false,
          totalCharacters: 13,
          kind: "final_answer",
          text: "Subagent 真实正文",
        },
      ],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [],
      liveStream: null,
      nextCursor: null,
    };
    const subagentDetail: TaskReadModel = {
      task: subagentTask,
      thread: {
        threadId: subagentTask.taskThreadId,
        workspaceId: "ws_demo",
        preferences: {
          providerId: "provider_test",
          modelId: "model_test",
          reasoningLevel: "medium",
          accessMode: "approval_required",
          collaborationMode: "default",
          titleSource: "manual",
        },
        title: subagentTask.taskName,
        status: "active",
        pinned: false,
        latestTurnStatus: "completed",
        latestTurnSeen: true,
        revision: 12,
        createdAt: "2026-09-10T10:00:00Z",
        updatedAt: "2026-09-10T10:00:05Z",
      },
      contextSeed: {
        contextSeedId: "seed_subagent",
        parentRevision: 8,
        inheritanceMode: "brief_only",
        taskBrief: [{ type: "text", text: "检查合同" }],
        inheritedContextSummary: null,
        inheritedContextPreview: [],
        fingerprint: "b".repeat(64),
        createdAt: "2026-09-10T10:00:00Z",
      },
      activities: [],
      mailbox: [],
      nextCursor: null,
    };
    mocks.useTaskController.mockReturnValue({
      tasks: [subagentTask],
      detail: subagentDetail,
      loading: false,
      detailLoading: false,
      refresh: vi.fn(),
      refreshDetail: vi.fn(),
      followupTurn: vi.fn(),
      cancel: vi.fn(),
    });
    const applySnapshot = vi.spyOn(useTimelineStore.getState(), "applySnapshot");
    let timelineCommitCount = 0;
    const onTimelineCommit = () => {
      timelineCommitCount += 1;
    };
    expect(
      useTimelineStore.getState().applyRuntimeStatus({
        status: "ready",
        generation: 1,
        serverInstanceId: "srv_workbench_test",
      }),
    ).toBe("applied");

    const props = makeProps({
      active: true,
      rootThreadId: "thr_root",
      parentThreadRevision: 8,
      selectedTab: "subagent:thr_subagent",
      openTabs: ["subagent:thr_subagent"],
      taskPreferencesPort: {
        update: vi.fn(async () => subagentDetail.thread.preferences!),
      },
    });
    const view = render(
      <>
        <WorkbenchHost {...props} />
        <TimelineSubscriptionProbe
          threadId={subagentTask.taskThreadId}
          onCommit={onTimelineCommit}
        />
      </>,
    );
    const taskControllerOptions = mocks.useTaskController.mock.calls.at(-1)?.[0] as {
      applyTranscriptSnapshot?: (snapshot: TimelineSnapshot) => boolean;
    };
    act(() => expect(taskControllerOptions.applyTranscriptSnapshot?.(subagentSnapshot)).toBe(true));

    await waitFor(() =>
      expect(screen.getByTestId("timeline-probe-thr_subagent").textContent).toBe(
        "12|Subagent 真实正文",
      ),
    );
    expect(applySnapshot).toHaveBeenCalledTimes(1);
    expect(timelineCommitCount).toBe(2);
    const taskPanelProps = (mocks.TaskDetailPanel.mock.calls as unknown as unknown[][]).at(
      -1,
    )?.[0] as {
      tab?: { taskKind?: string; taskThreadId?: string };
      conversation?: unknown;
    };
    expect(taskPanelProps.tab).toEqual(
      expect.objectContaining({ taskKind: "subagent", taskThreadId: "thr_subagent" }),
    );
    expect(taskPanelProps.conversation).toBeUndefined();

    view.rerender(
      <>
        <WorkbenchHost {...props} />
        <TimelineSubscriptionProbe
          threadId={subagentTask.taskThreadId}
          onCommit={onTimelineCommit}
        />
      </>,
    );
    expect(applySnapshot).toHaveBeenCalledTimes(1);
    applySnapshot.mockRestore();
  });

  /** 普通 child send 会经唯一 Timeline Store fence；RuntimeProvider mock 不构成会话就绪证明。@author kongweiguang */
  it("侧聊 Composer 的 /btw 绑定当前 child 来源而不是主 root", async () => {
    establishReadyTimelineRuntime();
    const sideTask: TaskSummary = {
      taskThreadId: "thr_side",
      parentThreadId: "thr_root",
      rootThreadId: "thr_root",
      originTurnId: null,
      taskName: "侧聊",
      depth: 1,
      taskKind: "side_task",
      lifecycle: "independent",
      state: "idle",
      revision: 6,
      latestActivitySequence: 1,
      unreadCount: 0,
      descendantCount: 0,
      runningDescendantCount: 0,
      needsAttentionCount: 0,
      latestSafeSummary: null,
      startedAt: null,
      completedAt: null,
      updatedAt: "2026-09-10T10:00:00Z",
    };
    const preferences = {
      providerId: "provider_side",
      modelId: "model_side",
      reasoningLevel: "high" as const,
      accessMode: "full_access" as const,
      collaborationMode: "default" as const,
      titleSource: "manual" as const,
    };
    const detail: TaskReadModel = {
      task: sideTask,
      thread: {
        threadId: sideTask.taskThreadId,
        workspaceId: "ws_demo",
        preferences,
        title: sideTask.taskName,
        status: "active",
        pinned: false,
        latestTurnStatus: null,
        latestTurnSeen: true,
        revision: sideTask.revision,
        createdAt: "2026-09-10T10:00:00Z",
        updatedAt: sideTask.updatedAt,
      },
      contextSeed: {
        contextSeedId: "seed_side",
        parentRevision: 4,
        inheritanceMode: "brief_only",
        taskBrief: [{ type: "text", text: "侧聊" }],
        inheritedContextSummary: null,
        inheritedContextPreview: [],
        fingerprint: "c".repeat(64),
        createdAt: "2026-09-10T10:00:00Z",
      },
      activities: [],
      mailbox: [],
      nextCursor: null,
    };
    const transcript: TimelineSnapshot = {
      threadId: sideTask.taskThreadId,
      revision: sideTask.revision,
      turns: [],
      items: [],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [],
      liveStream: null,
      nextCursor: null,
    };
    const nestedTask = {
      ...sideTask,
      taskThreadId: "thr_nested",
      parentThreadId: sideTask.taskThreadId,
    };
    let releaseReady!: () => void;
    const taskReady = new Promise<void>((resolve) => {
      releaseReady = resolve;
    });
    const createSideTask = vi.fn(async () => nestedTask);
    const waitForTaskReady = vi.fn(() => taskReady);
    const followup = vi.fn(async () => nestedTask);
    const followupTurn = vi.fn(async () => ({
      accepted: true as const,
      turnId: "turn_side_input",
      queued: true,
      threadRevision: sideTask.revision + 1,
    }));
    mocks.useTaskController.mockReturnValue({
      tasks: [sideTask],
      detail,
      transcript,
      readyTaskThreadId: sideTask.taskThreadId,
      loading: false,
      detailLoading: false,
      refresh: vi.fn(),
      refreshDetail: vi.fn(),
      createSideTask,
      waitForTaskReady,
      followup,
      followupTurn,
      close: vi.fn(),
    });

    render(
      <WorkbenchHost
        {...makeProps({
          active: true,
          rootThreadId: "thr_root",
          parentThreadRevision: 8,
          selectedTab: "side-task:thr_side",
          openTabs: ["side-task:thr_side"],
          taskPreferencesPort: {
            update: vi.fn(async () => preferences),
          },
        })}
      />,
    );

    const panelProps = (mocks.TaskDetailPanel.mock.calls as unknown as unknown[][]).at(-1)?.[0] as {
      composerEnvironment?: {
        slashCommands?: readonly {
          id: string;
          execute: (context: { argument: string }) => void | Promise<void>;
        }[];
      };
      conversation?: {
        send: (request: { text: string }) => Promise<void>;
      };
    };
    const btw = panelProps.composerEnvironment?.slashCommands?.find(
      (command) => command.id === "btw",
    );
    expect(btw).toBeDefined();
    let pendingBtw!: Promise<void>;
    await act(async () => {
      pendingBtw = btw?.execute({ argument: "从侧聊继续" }) as Promise<void>;
      await Promise.resolve();
    });
    expect(followup).not.toHaveBeenCalled();
    releaseReady();
    await act(async () => {
      await pendingBtw;
    });

    expect(createSideTask).toHaveBeenCalledWith({
      taskName: "侧聊",
      sourceThreadId: sideTask.taskThreadId,
      sourceThreadRevision: sideTask.revision,
      preferences: {
        providerId: preferences.providerId,
        modelId: preferences.modelId,
        reasoningLevel: preferences.reasoningLevel,
        accessMode: preferences.accessMode,
        collaborationMode: preferences.collaborationMode,
      },
    });
    expect(waitForTaskReady).toHaveBeenCalledWith(nestedTask.taskThreadId);
    expect(followup).toHaveBeenCalledWith(
      nestedTask,
      [{ type: "text", text: "从侧聊继续" }],
      sideTask.taskThreadId,
    );

    await act(async () => {
      await panelProps.conversation?.send({ text: "侧聊普通输入" });
    });
    expect(followupTurn).toHaveBeenCalledWith(
      sideTask,
      [{ type: "text", text: "侧聊普通输入" }],
      sideTask.taskThreadId,
    );
  });

  it("关闭 Task 实例只提交 Tab teardown，不调用 task cancel", () => {
    const cancel = vi.fn();
    mocks.useTaskController.mockReturnValue({
      tasks: [],
      loading: false,
      detailLoading: false,
      refresh: vi.fn(),
      cancel,
    });
    render(<WorkbenchHost {...makeProps()} />);
    const workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      onTabClose: (tab: unknown) => void;
    };
    workbenchProps.onTabClose({
      kind: "task",
      key: "subagent:thr_child",
      taskKind: "subagent",
      taskThreadId: "thr_child",
      rootThreadId: "thr_root",
      label: "检查",
    });
    expect(cancel).not.toHaveBeenCalled();
  });

  /** 侧聊 Tab 关闭必须等待 Task controller 的真实销毁 ACK，不能退化为仅释放观察。 */
  it("routes side-chat tab close through task controller close", async () => {
    const close = vi.fn(async () => undefined);
    const sideTask: TaskSummary = {
      taskThreadId: "thr_side",
      parentThreadId: "thr_root",
      rootThreadId: "thr_root",
      originTurnId: null,
      taskName: "侧聊",
      depth: 1,
      taskKind: "side_task",
      lifecycle: "independent",
      state: "idle",
      revision: 1,
      latestActivitySequence: 1,
      unreadCount: 0,
      descendantCount: 0,
      runningDescendantCount: 0,
      needsAttentionCount: 0,
      latestSafeSummary: null,
      startedAt: null,
      completedAt: null,
      updatedAt: "2026-09-10T10:00:00Z",
    };
    mocks.useTaskController.mockReturnValue({
      tasks: [sideTask],
      loading: false,
      detailLoading: false,
      refresh: vi.fn(),
      close,
    });
    render(
      <WorkbenchHost
        {...makeProps({
          rootThreadId: "thr_root",
          selectedTab: "side-task:thr_side",
          openTabs: ["side-task:thr_side"],
        })}
      />,
    );
    const workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      onTabClose: (tab: unknown) => Promise<void> | void;
    };

    await act(async () => {
      await workbenchProps.onTabClose({
        kind: "task",
        key: "side-task:thr_side",
        taskKind: "side_task",
        taskThreadId: "thr_side",
        rootThreadId: "thr_root",
        label: "侧聊",
      });
    });
    expect(close).toHaveBeenCalledExactlyOnceWith(sideTask);
  });

  /** 新侧聊先挂载无 token Composer，ready 后再发布焦点意图，保住首屏 disabled 的一次请求。@author kongweiguang */
  it("waits for the idle side task to be ready before publishing its Composer focus token", async () => {
    const sideTask = idleFocusSideTask();
    let releaseReady!: () => void;
    const taskReady = new Promise<void>((resolve) => {
      releaseReady = resolve;
    });
    const waitForTaskReady = vi.fn(() => taskReady);
    const createSideTask = vi.fn(async () => sideTask);
    const onOpenTabsChange = vi.fn();
    const onTabChange = vi.fn();
    mocks.useTaskController.mockReturnValue({
      tasks: [sideTask],
      loading: false,
      detailLoading: false,
      refresh: vi.fn(),
      waitForTaskReady,
      createSideTask,
      cancel: vi.fn(),
    });
    const props = makeProps({
      active: true,
      rootThreadId: "thr_root",
      parentThreadRevision: 4,
      selectedTab: "agents",
      openTabs: ["agents"],
      onOpenTabsChange,
      onTabChange,
    });
    const { rerender } = render(<WorkbenchHost {...props} />);
    const workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      onCreateSideTask: () => undefined;
    };

    expect(workbenchProps.onCreateSideTask()).toBeUndefined();
    expect(createSideTask).toHaveBeenCalledWith({
      taskName: "侧聊",
      sourceThreadId: "thr_root",
      sourceThreadRevision: undefined,
      preferences: undefined,
    });
    await waitFor(() => expect(waitForTaskReady).toHaveBeenCalledWith(sideTask.taskThreadId));
    await waitFor(() => {
      expect(onOpenTabsChange).toHaveBeenCalledWith(["agents", "side-task:thr_child"]);
      expect(onTabChange).toHaveBeenCalledWith("side-task:thr_child");
    });

    rerender(
      <WorkbenchHost
        {...props}
        selectedTab="side-task:thr_child"
        openTabs={["agents", "side-task:thr_child"]}
      />,
    );
    /** 读取最后一次真实 renderTaskView 注入的 token，确认 ready 前首屏未携带意图。@author kongweiguang */
    const latestPanelProps = (): { focusRequest?: number } | undefined => {
      const calls = mocks.TaskDetailPanel.mock.calls as unknown as Array<
        [{ focusRequest?: number }]
      >;
      return calls.at(-1)?.[0];
    };
    await waitFor(() => expect(latestPanelProps()).toBeDefined());
    expect(latestPanelProps()?.focusRequest).toBeUndefined();

    await act(async () => {
      releaseReady();
      await taskReady;
    });
    await waitFor(() => expect(latestPanelProps()?.focusRequest).toBe(1));
  });

  /**
   * @author kongweiguang
   * Controller 会随 closingTaskThreadId 更新身份；ready continuation 必须读最新 scope，即使关闭 ACK 未移除 Tab。
   */
  it("ignores a late ready focus after close starts while the side-task tab remains open", async () => {
    const sideTask = idleFocusSideTask();
    let releaseReady!: () => void;
    const taskReady = new Promise<void>((resolve) => {
      releaseReady = resolve;
    });
    const waitForTaskReady = vi.fn(() => taskReady);
    const createSideTask = vi.fn(async () => sideTask);
    const makeTaskController = (closingTaskThreadId?: string) => ({
      tasks: [sideTask],
      loading: false,
      detailLoading: false,
      closingTaskThreadId,
      refresh: vi.fn(),
      waitForTaskReady,
      createSideTask,
      cancel: vi.fn(),
    });
    const openController = makeTaskController();
    mocks.useTaskController.mockReturnValue(openController);
    const props = makeProps({
      active: true,
      rootThreadId: "thr_root",
      parentThreadRevision: 4,
      selectedTab: "agents",
      openTabs: ["agents"],
      onOpenTabsChange: vi.fn(),
      onTabChange: vi.fn(),
    });
    const { rerender } = render(<WorkbenchHost {...props} />);
    const workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      onCreateSideTask: () => undefined;
    };
    act(() => workbenchProps.onCreateSideTask());
    await waitFor(() => expect(waitForTaskReady).toHaveBeenCalledWith(sideTask.taskThreadId));

    const childProps = {
      ...props,
      selectedTab: "side-task:thr_child" as const,
      openTabs: ["agents", "side-task:thr_child"] as const,
    };
    rerender(<WorkbenchHost {...childProps} />);

    const closingController = makeTaskController(sideTask.taskThreadId);
    expect(closingController).not.toBe(openController);
    mocks.useTaskController.mockReturnValue(closingController);
    rerender(<WorkbenchHost {...childProps} />);
    const latestWorkbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      openTabs: readonly { key: string }[];
    };
    expect(latestWorkbenchProps.openTabs.some((tab) => tab.key === "side-task:thr_child")).toBe(
      true,
    );

    const latestPanelProps = (): { focusRequest?: number } | undefined => {
      const calls = mocks.TaskDetailPanel.mock.calls as unknown as Array<
        [{ focusRequest?: number }]
      >;
      return calls.at(-1)?.[0];
    };
    await waitFor(() => expect(latestPanelProps()).toBeDefined());
    expect(latestPanelProps()?.focusRequest).toBeUndefined();

    await act(async () => {
      releaseReady();
      await taskReady;
    });
    await waitFor(() => expect(latestPanelProps()?.focusRequest).toBeUndefined());
  });

  /** ready 迟到时按 Host 可见 Thread、选中 Tab 与 openTabs owner 栅栏丢弃意图，不在恢复时复活。@author kongweiguang */
  it.each([
    {
      label: "切换到其他标签",
      active: true,
      selectedTab: "agents",
      openTabs: ["agents", "side-task:thr_child"],
    },
    {
      label: "隐藏 Workbench",
      active: false,
      selectedTab: "side-task:thr_child",
      openTabs: ["agents", "side-task:thr_child"],
    },
    {
      label: "关闭目标标签",
      active: true,
      selectedTab: "agents",
      openTabs: ["agents"],
    },
  ] as const)("$label 后不发布迟到的侧聊焦点 token", async (scenario) => {
    const sideTask = idleFocusSideTask();
    let releaseReady!: () => void;
    const taskReady = new Promise<void>((resolve) => {
      releaseReady = resolve;
    });
    const waitForTaskReady = vi.fn(() => taskReady);
    mocks.useTaskController.mockReturnValue({
      tasks: [sideTask],
      loading: false,
      detailLoading: false,
      refresh: vi.fn(),
      waitForTaskReady,
      createSideTask: vi.fn(async () => sideTask),
      cancel: vi.fn(),
    });
    const props = makeProps({
      active: true,
      rootThreadId: "thr_root",
      parentThreadRevision: 4,
      selectedTab: "agents",
      openTabs: ["agents"],
      onOpenTabsChange: vi.fn(),
      onTabChange: vi.fn(),
    });
    const { rerender } = render(<WorkbenchHost {...props} />);
    const workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      onCreateSideTask: () => undefined;
    };
    act(() => workbenchProps.onCreateSideTask());
    await waitFor(() => expect(waitForTaskReady).toHaveBeenCalledWith(sideTask.taskThreadId));

    const childProps = {
      ...props,
      selectedTab: "side-task:thr_child" as const,
      openTabs: ["agents", "side-task:thr_child"] as const,
    };
    rerender(<WorkbenchHost {...childProps} />);
    /** 读取最后一次真实 renderTaskView 注入的 token，覆盖创建 ACK 与 ready 两个提交边界。@author kongweiguang */
    const latestPanelProps = (): { focusRequest?: number } | undefined => {
      const calls = mocks.TaskDetailPanel.mock.calls as unknown as Array<
        [{ focusRequest?: number }]
      >;
      return calls.at(-1)?.[0];
    };
    await waitFor(() => expect(latestPanelProps()).toBeDefined());
    expect(latestPanelProps()?.focusRequest).toBeUndefined();

    rerender(
      <WorkbenchHost
        {...props}
        active={scenario.active}
        selectedTab={scenario.selectedTab}
        openTabs={scenario.openTabs}
      />,
    );
    await act(async () => {
      releaseReady();
      await taskReady;
    });

    rerender(<WorkbenchHost {...childProps} active />);
    await waitFor(() => expect(latestPanelProps()?.focusRequest).toBeUndefined());
  });

  it("routes an existing side-task tab rename through the task controller", async () => {
    const rename = vi.fn(async () => undefined);
    const sideTask = {
      taskThreadId: "thr_child",
      parentThreadId: "thr_root",
      rootThreadId: "thr_root",
      originTurnId: null,
      taskName: "原名称",
      depth: 1,
      taskKind: "side_task" as const,
      lifecycle: "independent" as const,
      state: "completed" as const,
      revision: 2,
      latestActivitySequence: 1,
      unreadCount: 0,
      descendantCount: 0,
      runningDescendantCount: 0,
      needsAttentionCount: 0,
      latestSafeSummary: null,
      startedAt: null,
      completedAt: "2026-09-07T10:00:00Z",
      updatedAt: "2026-09-07T10:00:00Z",
    };
    mocks.useTaskController.mockReturnValue({
      tasks: [sideTask],
      loading: false,
      detailLoading: false,
      refresh: vi.fn(),
      rename,
      cancel: vi.fn(),
    });
    render(
      <WorkbenchHost
        {...makeProps({
          rootThreadId: "thr_root",
          parentThreadRevision: 4,
          selectedTab: "side-task:thr_child",
          openTabs: ["side-task:thr_child"],
        })}
      />,
    );
    const workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      selectedTab: unknown;
      onTaskTabRename: (tab: unknown, label: string) => Promise<void>;
    };

    await act(async () => {
      await workbenchProps.onTaskTabRename(workbenchProps.selectedTab, "新名称");
    });

    expect(rename).toHaveBeenCalledWith(sideTask, "新名称");
  });

  it("gates the Plan capability and wires the Plan resume command", () => {
    const resumePlan = vi.fn(async () => true);
    const plan = makePlanController({ resumePlan });
    const { rerender } = render(<WorkbenchHost {...makeProps({ plan })} />);
    let workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      views: { plan?: { props?: { onResumePlan?: () => Promise<boolean> } } };
    };
    expect(workbenchProps.views.plan).toBeUndefined();

    rerender(<WorkbenchHost {...makeProps({ planAvailable: true, plan })} />);
    workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      views: { plan?: { props?: { onResumePlan?: () => Promise<boolean> } } };
    };
    expect(workbenchProps.views.plan).toBeDefined();
    expect(workbenchProps.views.plan?.props?.onResumePlan).toBe(resumePlan);
  });
});
