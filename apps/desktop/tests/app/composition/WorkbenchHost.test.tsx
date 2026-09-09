// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, render, waitFor } from "@testing-library/react";
import type { ComponentProps } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WorkbenchHost } from "@/app/composition/WorkbenchHost";
import type { GoalController } from "@/features/goals";

const mocks = vi.hoisted(() => ({
  useReviewController: vi.fn(),
  usePreviewController: vi.fn(),
  useFilesController: vi.fn(),
  useJaWorkbench: vi.fn(),
  useTerminalWorkspaceLifecycle: vi.fn(),
  useTaskController: vi.fn(),
  Workbench: vi.fn((props: unknown) => {
    void props;
    return null;
  }),
  FilesWorkspace: vi.fn((props: unknown) => {
    void props;
    return null;
  }),
}));

vi.mock("sonner", () => ({ toast: { error: vi.fn() } }));
vi.mock("@/features/workbench", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/workbench")>()),
  Workbench: mocks.Workbench,
}));
vi.mock("@/features/tasks", () => ({
  SubagentOverview: () => null,
  TaskDetailPanel: () => null,
  useTaskController: mocks.useTaskController,
}));
vi.mock("@/app/RuntimeProvider", () => ({
  useRuntimeLifecycle: () => ({ queryRuntime: vi.fn() }),
  useRuntimeTurns: () => ({ approvalRespond: vi.fn(), resumeTurn: vi.fn() }),
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
  useReviewController: mocks.useReviewController,
}));
vi.mock("@/features/workbench/terminal", () => ({
  LocalTerminalLayoutStorage: class {},
  useTerminalLayoutPersistence: () => [{ kind: "single", rootPaneId: "pane_1" }, vi.fn()],
}));
vi.mock("@/app/application/createNotifyingFilesOperations", () => ({
  createNotifyingFilesOperations: () => ({}),
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
  vi.clearAllMocks();
});

beforeEach(() => {
  mocks.useReviewController.mockReturnValue({
    viewModel: {
      state: { catalog: undefined, source: { kind: "uncommitted" }, layerFilter: "all" },
      sourceOptions: [{ kind: "uncommitted" }],
    },
    actions: { setSource: vi.fn(), setLayerFilter: vi.fn() },
  });
  mocks.usePreviewController.mockReturnValue({
    viewModel: {},
    actions: { attachment: { dismiss: vi.fn() } },
  });
  mocks.useFilesController.mockReturnValue({
    viewModel: { documents: {}, openPaths: [] },
    actions: { selectNode: vi.fn(), toggleDirectory: vi.fn() },
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

/** 构造完整 Goal controller，composition 测试只替换需要断言的恢复动作。 */
function makeGoalController(overrides: Partial<GoalController> = {}): GoalController {
  return {
    model: {
      goal: {
        goalId: "goal_1",
        ownerThreadId: "thr_one",
        revision: 7,
        status: "paused",
        phase: "needs_attention",
        objective: "验证目标恢复接线",
        goalDefinitionRevision: 1,
        acceptanceCriteria: [],
        activePlanId: null,
        activePlanRevisionId: null,
        activePlanHash: null,
        currentStepId: null,
        completedRequiredSteps: 0,
        totalRequiredSteps: 0,
        attentionSummary: "验收仍有缺口",
        updatedAt: "2026-09-04T10:10:00+08:00",
      },
      planState: null,
      plan: null,
      draft: null,
      inputRequest: null,
      evaluation: null,
    },
    planModel: undefined,
    revisions: [],
    evidence: [],
    loading: false,
    error: undefined,
    busyAction: undefined,
    refresh: vi.fn(async () => undefined),
    create: vi.fn(async () => true),
    createPlan: vi.fn(async () => true),
    pause: vi.fn(async () => true),
    resume: vi.fn(async () => true),
    stop: vi.fn(async () => true),
    respondInput: vi.fn(async () => true),
    saveDraft: vi.fn(async () => true),
    discardDraft: vi.fn(async () => true),
    propose: vi.fn(async () => true),
    approve: vi.fn(async () => true),
    execute: vi.fn(async () => true),
    attachPlan: vi.fn(async () => true),
    detachPlan: vi.fn(async () => true),
    reject: vi.fn(async () => true),
    ...overrides,
  };
}

describe("WorkbenchHost capability activation", () => {
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

  it("keeps a draft name in its stable tab descriptor without calling persistent rename", async () => {
    const rename = vi.fn();
    mocks.useTaskController.mockReturnValue({
      tasks: [],
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
          selectedTab: "side-task:draft_12345678",
          openTabs: ["side-task:draft_12345678"],
        })}
      />,
    );
    let workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      selectedTab: { label: string; key: string };
      onTaskTabRename: (tab: unknown, label: string) => Promise<void>;
    };

    await act(async () => {
      await workbenchProps.onTaskTabRename(workbenchProps.selectedTab, "资料核对");
    });

    workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as typeof workbenchProps;
    expect(workbenchProps.selectedTab).toMatchObject({
      key: "side-task:draft_12345678",
      label: "资料核对",
    });
    expect(rename).not.toHaveBeenCalled();
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

  it("gates the Plan capability and wires needs-attention continuation to resume", () => {
    const resume = vi.fn(async () => true);
    const goal = makeGoalController({ resume });
    const { rerender } = render(<WorkbenchHost {...makeProps({ goal })} />);
    let workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      views: { plan?: { props?: { onContinue?: () => Promise<boolean> } } };
    };
    expect(workbenchProps.views.plan).toBeUndefined();

    rerender(<WorkbenchHost {...makeProps({ planGoalAvailable: true, goal })} />);
    workbenchProps = mocks.Workbench.mock.calls.at(-1)?.at(0) as unknown as {
      views: { plan?: { props?: { onContinue?: () => Promise<boolean> } } };
    };
    expect(workbenchProps.views.plan).toBeDefined();
    expect(workbenchProps.views.plan?.props?.onContinue).toBe(resume);
  });
});
