// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useLayoutEffect, useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  ReviewController,
  ReviewControllerState,
} from "@/features/workbench/review/application/useReviewController";
import {
  type AiReviewController,
  type AiReviewReport,
  type AiReviewRun,
  useAiReviewController,
} from "@/features/workbench/review/application/useAiReviewController";
import type { AiReviewGateway } from "@/features/workbench/review/domain/aiReview";
import type {
  ReviewFile,
  ReviewFileDiff,
  ReviewSnapshot,
} from "@/features/workbench/review/domain/types";
import type { ReviewPanelViewProps } from "@/features/workbench/review/ui/ReviewPanelView";
import { ReviewPanelView } from "@/features/workbench/review/ui/ReviewPanelView";

vi.mock("@/features/workbench/editor", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/workbench/editor")>()),
  DiffViewer: ({
    filePath,
    original,
    modified,
  }: {
    filePath: string;
    original: string;
    modified: string;
  }) => (
    <div role="region" aria-label={`只读 Diff ${filePath}`}>
      {original}→{modified}
    </div>
  ),
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

/** 由 ResizeObserver 提交审阅容器宽度，验证布局不依赖整个窗口 viewport。 */
function stubReviewWidth(width: number): void {
  vi.stubGlobal(
    "ResizeObserver",
    class {
      readonly callback: ResizeObserverCallback;

      constructor(callback: ResizeObserverCallback) {
        this.callback = callback;
      }

      observe(target: Element): void {
        this.callback([{ target, contentRect: { width } } as ResizeObserverEntry], this);
      }

      disconnect(): void {}

      unobserve(): void {}
    },
  );
}

const file: ReviewFile = {
  fileId: "file_main",
  layer: "unstaged",
  path: "src/main.rs",
  oldPath: null,
  status: "modified",
  additions: 2,
  deletions: 1,
  binary: false,
  truncated: false,
  hunks: [
    {
      hunkId: "hunk_main",
      header: "@@ -1 +1,2 @@",
      oldStart: 1,
      oldLines: 1,
      newStart: 1,
      newLines: 2,
    },
  ],
};

const snapshot: ReviewSnapshot = {
  workspaceId: "ws_demo",
  source: { kind: "unstaged" },
  revision: "rev_unstaged",
  files: [file],
  stats: { files: 1, additions: 2, deletions: 1, binaryFiles: 0, truncated: false },
  capabilities: { stage: true, unstage: false, revert: true },
};

const diff: ReviewFileDiff = {
  workspaceId: "ws_demo",
  source: snapshot.source,
  revision: snapshot.revision,
  fileId: file.fileId,
  layer: file.layer,
  path: file.path,
  oldPath: null,
  status: "modified",
  binary: false,
  truncated: false,
  original: "old",
  modified: "new",
  unified: "@@ -1 +1 @@\n-old\n+new",
  hunks: file.hunks,
  lines: [
    { kind: "deletion", oldLine: 1, newLine: null, text: "old" },
    { kind: "addition", oldLine: null, newLine: 1, text: "new" },
  ],
};

/** 构造纯 UI controller 投影，测试不会触发 adapter 或 Tauri。 */
function makeController(overrides: Partial<ReviewControllerState> = {}): ReviewController {
  const state: ReviewControllerState = {
    loading: false,
    catalogLoading: false,
    diffLoading: false,
    source: snapshot.source,
    catalog: {
      workspaceId: "ws_demo",
      repositoryName: "ja",
      currentBranch: "main",
      headCommitId: "abc123",
      baseRefs: [{ refId: "main", label: "main", kind: "base" }],
      commits: [],
    },
    snapshot,
    diff,
    selectedFileId: file.fileId,
    filter: "all",
    layerFilter: "all",
    query: "",
    viewMode: "split",
    drawerOpen: false,
    error: undefined,
    diffError: undefined,
    notice: undefined,
    pendingOperationIds: new Set<string>(),
    ...overrides,
  };
  return {
    viewModel: {
      state,
      sourceOptions:
        state.source.kind === "branch"
          ? [{ kind: "unstaged" }, state.source]
          : [state.source, { kind: "branch", refId: "main" }],
      visibleFiles: state.snapshot?.files ?? [],
      selectedFile: state.snapshot?.files.find(
        (candidate) => candidate.fileId === state.selectedFileId,
      ),
    },
    actions: {
      refresh: vi.fn(),
      setSource: vi.fn(),
      selectFile: vi.fn(),
      setFilter: vi.fn(),
      setLayerFilter: vi.fn(),
      setQuery: vi.fn(),
      setViewMode: vi.fn(),
      setDrawerOpen: vi.fn(),
      applyAction: vi.fn(async () => undefined),
      cancelOperation: vi.fn(async () => undefined),
      clearNotice: vi.fn(),
    },
  };
}

/** 为贯通 UI 用例保留真实 AI Review controller，并只替换当前 Git snapshot revision。 */
function AiReviewPanelHarness({
  controllerRef,
  gateway,
  onRepairFinding,
}: {
  controllerRef: {
    current?: {
      readonly aiReview: AiReviewController;
      readonly review: ReviewController;
      readonly setSnapshotRevision: (revision: string) => void;
    };
  };
  gateway: AiReviewGateway;
  onRepairFinding: NonNullable<ReviewPanelViewProps["onRepairFinding"]>;
}) {
  const [snapshotRevision, setSnapshotRevision] = useState("git_revision_1");
  const aiReview = useAiReviewController({
    active: true,
    workspaceId: "ws_demo",
    threadId: "thread_demo",
    threadRevision: 4,
    source: { kind: "unstaged" },
    snapshotRevision,
    gateway,
  });
  const review = makeController({
    snapshot: { ...snapshot, revision: snapshotRevision },
    diff: { ...diff, revision: snapshotRevision },
    viewMode: "split",
  });
  useLayoutEffect(() => {
    controllerRef.current = { aiReview, review, setSnapshotRevision };
  }, [aiReview, controllerRef, review, setSnapshotRevision]);
  return (
    <ReviewPanelView
      viewModel={review.viewModel}
      actions={review.actions}
      aiReview={aiReview}
      onRepairFinding={onRepairFinding}
    />
  );
}

/** 给只读状态 UI 测试提供最小控制器投影，避免启动 Runtime 或读取 Git。 */
function makeAiReviewController(run: AiReviewRun): AiReviewController {
  return {
    state: { runs: [run], selectedRunId: run.runId },
    selectedRun: run,
    ready: true,
    busy: false,
    actions: {
      start: vi.fn(async () => undefined),
      retry: vi.fn(async () => undefined),
      refresh: vi.fn(async () => undefined),
      loadOlderHistory: vi.fn(async () => undefined),
      retryHistory: vi.fn(async () => undefined),
      cancel: vi.fn(async () => undefined),
      select: vi.fn(),
    },
  };
}

describe("ReviewPanelView", () => {
  /** 首次审查前仅保留启动入口，避免结果面板以空报告占据 Workbench 空间。 */
  it("未选择审查记录时隐藏结果面板", () => {
    const controller = makeController();
    const aiReview: AiReviewController = {
      state: { runs: [] },
      selectedRun: undefined,
      ready: true,
      busy: false,
      actions: {
        start: vi.fn(async () => undefined),
        retry: vi.fn(async () => undefined),
        refresh: vi.fn(async () => undefined),
        loadOlderHistory: vi.fn(async () => undefined),
        retryHistory: vi.fn(async () => undefined),
        cancel: vi.fn(async () => undefined),
        select: vi.fn(),
      },
    };
    render(
      <ReviewPanelView
        viewModel={controller.viewModel}
        actions={controller.actions}
        aiReview={aiReview}
      />,
    );

    expect(screen.getByRole("button", { name: "AI 审查" })).toBeEnabled();
    expect(screen.queryByRole("region", { name: "AI 审查结果" })).not.toBeInTheDocument();
    expect(screen.queryByText("尚无审查报告。")).not.toBeInTheDocument();
  });

  /** 报告选择进入菜单后仍保留真实单选与焦点回程，不触发新的审查或 Git 写入。 */
  it("在更多中切换历史审查并返回入口", async () => {
    const user = userEvent.setup();
    const currentRun: AiReviewRun = {
      runId: "current",
      status: "completed",
      createdAt: "2026-10-07T02:00:00Z",
    };
    const previousRun: AiReviewRun = {
      runId: "previous",
      status: "completed",
      historical: true,
      createdAt: "2026-10-06T02:00:00Z",
    };
    const baseReview = makeAiReviewController(currentRun);
    const aiReview: AiReviewController = {
      ...baseReview,
      state: { ...baseReview.state, runs: [currentRun, previousRun] },
    };
    const controller = makeController();
    render(
      <ReviewPanelView
        viewModel={controller.viewModel}
        actions={controller.actions}
        aiReview={aiReview}
      />,
    );
    const more = screen.getByRole("button", { name: "审查更多操作" });
    expect(screen.queryByRole("combobox", { name: "审查记录" })).not.toBeInTheDocument();
    await user.click(more);
    screen.getByRole("menuitem", { name: "审查记录" }).focus();
    await user.keyboard("{ArrowRight}");
    expect(await screen.findByRole("menuitemradio", { name: "审查 2 · 已完成" })).toHaveAttribute(
      "aria-checked",
      "true",
    );
    await user.click(screen.getByRole("menuitemradio", { name: "历史审查 1 · 已完成" }));
    expect(aiReview.actions.select).toHaveBeenCalledWith("previous");
    expect(aiReview.actions.start).not.toHaveBeenCalled();
    expect(controller.actions.applyAction).not.toHaveBeenCalled();
    await waitFor(() => expect(more).toHaveFocus());
  });

  /** Git 写入互斥不应禁用整个更多菜单，布局和历史阅读仍可用。 */
  it("写操作进行中只禁用 mutation 菜单项", async () => {
    const user = userEvent.setup();
    const controller = makeController({ pendingOperationIds: new Set(["operation"]) });
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);
    await user.click(screen.getByRole("button", { name: "审查更多操作" }));
    expect(screen.getByRole("menuitemradio", { name: "统一 Diff" })).not.toHaveAttribute(
      "data-disabled",
    );
    const stage = screen.getByRole("menuitem", { name: "暂存文件" });
    expect(stage).toHaveAttribute("data-disabled");
    await user.click(stage);
    expect(controller.actions.applyAction).not.toHaveBeenCalled();
  });

  /** 主动作在工作时变为取消，受理期间锁定同一入口，避免重复提交。 */
  it("主入口取消运行中的审查并阻止重复取消", async () => {
    const user = userEvent.setup();
    const run: AiReviewRun = {
      runId: "active",
      turnId: "turn",
      status: "running",
      createdAt: "2026-10-07T02:00:00Z",
    };
    const aiReview = makeAiReviewController(run);
    const controller = makeController();
    const view = render(
      <ReviewPanelView
        viewModel={controller.viewModel}
        actions={controller.actions}
        aiReview={aiReview}
      />,
    );
    await user.click(screen.getByRole("button", { name: "取消 AI 审查" }));
    expect(aiReview.actions.cancel).toHaveBeenCalledWith("active");
    expect(aiReview.actions.start).not.toHaveBeenCalled();
    view.rerender(
      <ReviewPanelView
        viewModel={controller.viewModel}
        actions={controller.actions}
        aiReview={{ ...aiReview, selectedRun: { ...run, cancelPending: true } }}
      />,
    );
    expect(screen.getByRole("button", { name: "取消 AI 审查" })).toBeDisabled();
  });

  /** 中断仍被控制器计为 busy；继续原快照不能被新的启动禁用条件锁死。 */
  it("中断的冻结快照仍可由主入口继续", async () => {
    const user = userEvent.setup();
    const baseReview = makeAiReviewController({
      runId: "interrupted",
      status: "interrupted",
      createdAt: "2026-10-07T02:00:00Z",
    });
    const aiReview = { ...baseReview, busy: true };
    const controller = makeController();
    render(
      <ReviewPanelView
        viewModel={controller.viewModel}
        actions={controller.actions}
        aiReview={aiReview}
      />,
    );
    const retry = screen.getByRole("button", { name: "重试 AI 审查" });
    expect(retry).toBeEnabled();
    await user.click(retry);
    expect(aiReview.actions.retry).toHaveBeenCalledWith("interrupted");
    expect(aiReview.actions.start).not.toHaveBeenCalled();
  });

  /** 已签发 Turn 的报告读取错误仍能重读，同时保留取消真实 Turn 的入口。 */
  it("运行中报告读取失败保留重读和取消", async () => {
    const user = userEvent.setup();
    const aiReview = makeAiReviewController({
      runId: "running",
      turnId: "turn_running",
      status: "running",
      error: { code: "UNAVAILABLE", message: "报告读取失败。", retryable: true },
      createdAt: "2026-10-07T02:00:00Z",
    });
    const controller = makeController();
    render(
      <ReviewPanelView
        viewModel={controller.viewModel}
        actions={controller.actions}
        aiReview={aiReview}
      />,
    );
    expect(screen.getByRole("button", { name: "取消 AI 审查" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "重试读取" }));
    expect(aiReview.actions.retry).toHaveBeenCalledWith("running");
    expect(aiReview.actions.cancel).not.toHaveBeenCalled();
  });

  /** 没有选中报告时，历史读取错误仍在可见内容区提供恢复动作。 */
  it("历史读取失败保留就近重试", async () => {
    const user = userEvent.setup();
    const baseReview = makeAiReviewController({
      runId: "history",
      status: "completed",
      createdAt: "2026-10-07T02:00:00Z",
    });
    const aiReview: AiReviewController = {
      ...baseReview,
      selectedRun: undefined,
      state: {
        ...baseReview.state,
        runs: [],
        historyError: {
          code: "UNAVAILABLE",
          message: "无法读取审查记录。",
          retryable: true,
        },
      },
    };
    const controller = makeController();
    render(
      <ReviewPanelView
        viewModel={controller.viewModel}
        actions={controller.actions}
        aiReview={aiReview}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("无法读取审查记录。");
    await user.click(screen.getByRole("button", { name: "重试读取" }));
    expect(aiReview.actions.retryHistory).toHaveBeenCalledOnce();
  });

  /** 终态历史 Turn 没有已提交 artifact 时明确报告不可用，不伪装成零发现并提供当前内容重审。 */
  it("shows an explicit missing-report state for a terminal history run", async () => {
    const user = userEvent.setup();
    const aiReview = makeAiReviewController({
      runId: "history:turn_unsubmitted",
      turnId: "turn_unsubmitted",
      status: "completed",
      historical: true,
      reportExpected: false,
      createdAt: "2026-10-06T02:00:00Z",
    });
    const controller = makeController();
    render(
      <ReviewPanelView
        viewModel={controller.viewModel}
        actions={controller.actions}
        aiReview={aiReview}
      />,
    );

    expect(
      screen.getByText("此审查已结束，但未提交有效报告；不能据此判断为零发现。"),
    ).toHaveAttribute("role", "status");
    expect(screen.queryByText("0 项发现")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "定位" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "修复" })).not.toBeInTheDocument();
    const reviewCurrent = screen.getByRole("button", { name: "重新审查当前内容" });
    expect(reviewCurrent).toBeEnabled();
    await user.click(reviewCurrent);
    expect(aiReview.actions.start).toHaveBeenCalledOnce();
  });

  /** 已签发 Turn 的失败或取消没有报告时，面板须说明结果未知并提供就近重审。 */
  it.each([
    {
      status: "failed" as const,
      feedbackRole: "alert" as const,
      message: "本次审查失败，未生成报告。错误详情见对话，不能据此判断为零发现。",
    },
    {
      status: "cancelled" as const,
      feedbackRole: "status" as const,
      message: "本次审查已取消，未生成报告；不能据此判断为零发现。",
    },
  ])("终态 $status 且无报告时说明结果未知并可重审", async ({ status, feedbackRole, message }) => {
    const user = userEvent.setup();
    const aiReview = makeAiReviewController({
      runId: `run_${status}`,
      turnId: `turn_${status}`,
      status,
      createdAt: "2026-10-06T02:00:00Z",
    });
    const controller = makeController();
    render(
      <ReviewPanelView
        viewModel={controller.viewModel}
        actions={controller.actions}
        aiReview={aiReview}
      />,
    );

    expect(screen.getByText(message)).toHaveAttribute("role", feedbackRole);
    expect(screen.queryByText("0 项发现")).not.toBeInTheDocument();
    expect(screen.queryByText("未发现需要处理的问题。")).not.toBeInTheDocument();
    const reReview = screen.getByRole("button", { name: "重新审查当前内容" });
    expect(reReview).toBeEnabled();
    await user.click(reReview);
    expect(aiReview.actions.start).toHaveBeenCalledOnce();
  });

  /** 零发现摘要须限定到已审文件，不能把部分覆盖包装成全仓结论。 */
  it("qualifies an empty findings summary when report coverage is partial", () => {
    const partialReport: AiReviewReport = {
      reportId: "report_partial_empty",
      snapshotId: "rev_0123456789abcdef01234567",
      snapshotHash: "a".repeat(64),
      revision: "b".repeat(64),
      summary: "",
      coverage: {
        complete: false,
        files: 2,
        bytes: 128,
        maxFiles: 2_000,
        maxBytes: 64 * 1024 * 1024,
        partialReasons: ["binary_file"],
        filesReviewed: [{ path: "src/main.rs", layer: "unstaged" }],
        reviewComplete: false,
      },
      findings: [],
    };
    const aiReview = makeAiReviewController({
      runId: "run_partial_empty",
      turnId: "turn_partial_empty",
      status: "completed",
      report: partialReport,
      createdAt: "2026-10-06T02:00:00Z",
    });
    const controller = makeController();
    render(
      <ReviewPanelView
        viewModel={controller.viewModel}
        actions={controller.actions}
        aiReview={aiReview}
      />,
    );

    expect(screen.getByText("在已审内容中未发现需要处理的问题。")).toBeInTheDocument();
    expect(screen.getByText("覆盖不完整：1/2 个文件 · 包含二进制文件")).toBeInTheDocument();
    expect(screen.queryByText("未发现需要处理的问题。")).not.toBeInTheDocument();
  });

  /** 历史 Turn 尚未终止时保留服务端工作状态，不提前显示终态的无报告结论。 */
  it("keeps the actual Turn status while a history review is still running", () => {
    const aiReview = makeAiReviewController({
      runId: "history:turn_running",
      turnId: "turn_running",
      status: "running",
      historical: true,
      reportExpected: false,
      createdAt: "2026-10-06T02:00:00Z",
    });
    const controller = makeController();
    render(
      <ReviewPanelView
        viewModel={controller.viewModel}
        actions={controller.actions}
        aiReview={aiReview}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("正在工作");
    expect(screen.queryByText(/未提交有效报告/u)).not.toBeInTheDocument();
  });

  /** 历史报告只恢复服务端正文；定位展示冻结原始行号，关闭后回焦且不开放修复。 */
  it("locates a recovered history finding in its read-only frozen evidence", async () => {
    const user = userEvent.setup();
    const historicalReport: AiReviewReport = {
      reportId: "report_history",
      snapshotId: "rev_history_0123456789abcdef01234567",
      snapshotHash: "c".repeat(64),
      revision: "git_revision_history",
      summary: "历史审查发现一处边界问题。",
      coverage: {
        complete: true,
        files: 1,
        bytes: 96,
        maxFiles: 2_000,
        maxBytes: 64 * 1024 * 1024,
        partialReasons: [],
        filesReviewed: [{ path: "src/old.rs", layer: "comparison" }],
        reviewComplete: true,
      },
      findings: [
        {
          findingId: "finding_history",
          severity: "P1",
          title: "读取越界值",
          body: "边界检查缺失。",
          path: "src/old.rs",
          layer: "comparison",
          startLine: 43,
          endLine: 44,
          side: "base",
          evidence: "let value = rows[index];\nreturn value;",
        },
      ],
    };
    const gateway: AiReviewGateway = {
      captureSnapshot: vi.fn(async () => {
        throw new Error("historical reports must not capture a current snapshot");
      }),
      startAiReview: vi.fn(async () => {
        throw new Error("historical reports must not start a new review");
      }),
      readAiReview: vi.fn(async ({ threadId, turnId }) => ({
        threadId,
        turnId,
        status: "completed" as const,
        report: historicalReport,
      })),
      readHistoryPage: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 15,
        turns: [
          {
            turnId: "turn_history",
            status: "completed" as const,
            requestedAt: "2026-10-06T02:00:00Z",
          },
        ],
        items: [
          {
            itemId: "item_history_report",
            turnId: "turn_history",
            createdAt: "2026-10-06T02:01:00Z",
            toolName: "review_report_submit",
            status: "success" as const,
            artifactId: "artifact_history_report",
          },
        ],
        nextCursor: null,
      })),
    };
    const controllerRef: {
      current?: {
        readonly aiReview: AiReviewController;
        readonly review: ReviewController;
        readonly setSnapshotRevision: (revision: string) => void;
      };
    } = {};
    render(
      <AiReviewPanelHarness
        controllerRef={controllerRef}
        gateway={gateway}
        onRepairFinding={vi.fn(async () => ({ turnId: "turn_fix", queued: false }))}
      />,
    );

    expect(await screen.findByText(historicalReport.summary)).toBeVisible();
    expect(gateway.readAiReview).toHaveBeenCalledWith({
      threadId: "thread_demo",
      turnId: "turn_history",
    });
    expect(screen.queryByRole("button", { name: "修复" })).not.toBeInTheDocument();
    const locate = screen.getByRole("button", { name: "定位" });
    expect(locate).toBeEnabled();
    await user.click(locate);

    const dialog = await screen.findByRole("dialog", { name: "冻结发现证据" });
    expect(
      within(dialog).getByText("仅包含审查报告引用的证据行，不是完整 Diff 或当前文件。"),
    ).toBeVisible();
    expect(within(dialog).getByText(/第 43-44 行/u)).toBeVisible();
    expect(await within(dialog).findByText("43", { selector: ".cm-gutterElement" })).toBeVisible();
    expect(within(dialog).getByRole("textbox")).toHaveTextContent("let value = rows[index];");
    expect(within(dialog).queryByRole("button", { name: "修复" })).not.toBeInTheDocument();

    await user.keyboard("{Escape}");
    await waitFor(() => expect(locate).toHaveFocus());

    await user.click(locate);
    const reopenedDialog = await screen.findByRole("dialog", { name: "冻结发现证据" });
    await user.click(within(reopenedDialog).getByRole("button", { name: "关闭冻结证据" }));
    await waitFor(() => expect(locate).toHaveFocus());
    expect(screen.queryByRole("dialog", { name: "冻结发现证据" })).not.toBeInTheDocument();
  });

  /** 用真实 Review controller 验证 start 错误优先于阶段提示，并保留同 run 的可重试入口。 */
  it("只显示一次审查启动错误并可重试", async () => {
    const user = userEvent.setup();
    let startAttempts = 0;
    const gateway: AiReviewGateway = {
      captureSnapshot: vi.fn(async (input) => ({
        workspaceId: input.workspaceId,
        snapshotId: "rev_000000000000000000000001",
        snapshotHash: "1".repeat(64),
        revision: input.expectedSnapshotRevision,
        attachmentId: "att_review_start_error",
        coverage: {
          complete: true,
          files: 1,
          bytes: 128,
          maxFiles: 2_000,
          maxBytes: 64 * 1024 * 1024,
          partialReasons: [],
        },
      })),
      startAiReview: vi.fn(async () => {
        startAttempts += 1;
        if (startAttempts === 1) throw { code: "REVIEW_STALE" };
        return {
          threadId: "thread_demo",
          turnId: "turn_retry",
          threadRevision: 6,
          queued: false,
        };
      }),
      readAiReview: vi.fn(async ({ threadId, turnId }) => ({
        threadId,
        turnId,
        status: "running" as const,
        report: null,
      })),
      readHistoryPage: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 16,
        turns: [],
        items: [],
        nextCursor: null,
      })),
    };
    const controllerRef: {
      current?: {
        readonly aiReview: AiReviewController;
        readonly review: ReviewController;
        readonly setSnapshotRevision: (revision: string) => void;
      };
    } = {};
    render(
      <AiReviewPanelHarness
        controllerRef={controllerRef}
        gateway={gateway}
        onRepairFinding={vi.fn(async () => ({ turnId: "turn_fix", queued: false }))}
      />,
    );

    const startReview = await screen.findByRole("button", { name: "AI 审查" });
    expect(startReview).toBeEnabled();
    await user.click(startReview);
    const failure = "工作区已经变化，请重新读取 Review。";
    expect(await screen.findByRole("alert")).toHaveTextContent(failure);
    expect(screen.getAllByText(failure)).toHaveLength(1);
    const retry = screen.getByRole("button", { name: "重试 AI 审查" });
    expect(retry).toBeEnabled();

    await user.click(retry);
    await waitFor(() => expect(gateway.startAiReview).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  /** 审查结果定位与修复；异步回执按报告隔离，Git revision 变化后禁修旧报告并重审。 */
  it("completes review, locate, repair, stale detection, and re-review on a new snapshot", async () => {
    const user = userEvent.setup();
    const captures: {
      readonly snapshotId: string;
      readonly snapshotHash: string;
      readonly revision: string;
      readonly attachmentId: string;
    }[] = [];
    const turnCaptures = new Map<string, (typeof captures)[number]>();
    let turnNumber = 0;
    const gateway: AiReviewGateway = {
      captureSnapshot: vi.fn(async (input) => {
        const capture = {
          snapshotId: `rev_${String(captures.length + 1).padStart(24, "0")}`,
          snapshotHash: String(captures.length + 1)
            .repeat(64)
            .slice(0, 64),
          revision: `frozen_${input.expectedSnapshotRevision}`,
          attachmentId: `att_review_${captures.length + 1}`,
        };
        captures.push(capture);
        return {
          workspaceId: input.workspaceId,
          ...capture,
          coverage: {
            complete: true,
            files: 1,
            bytes: 128,
            maxFiles: 2_000,
            maxBytes: 64 * 1024 * 1024,
            partialReasons: [],
          },
        };
      }),
      startAiReview: vi.fn(async () => {
        turnNumber += 1;
        const turnId = `turn_review_${turnNumber}`;
        turnCaptures.set(turnId, captures.at(-1)!);
        return {
          threadId: "thread_demo",
          turnId,
          threadRevision: 4 + turnNumber,
          queued: false,
        };
      }),
      readAiReview: vi.fn(async ({ turnId }) => {
        const capture = turnCaptures.get(turnId)!;
        const report: AiReviewReport = {
          reportId: `report_${turnId}`,
          snapshotId: capture.snapshotId,
          snapshotHash: capture.snapshotHash,
          revision: capture.revision,
          summary: `基于冻结工件 ${capture.revision} 的审查报告。`,
          coverage: {
            complete: true,
            files: 1,
            bytes: 128,
            maxFiles: 2_000,
            maxBytes: 64 * 1024 * 1024,
            partialReasons: [],
            filesReviewed: [{ path: "src/main.rs", layer: "unstaged" }],
            reviewComplete: true,
          },
          findings: [
            {
              findingId: "finding_shared",
              severity: "P1",
              title: "缺少边界校验",
              body: "输入值可能越界。",
              path: "src/main.rs",
              layer: "unstaged",
              startLine: 1,
              endLine: 1,
              side: "current",
              evidence: "索引直接来自用户输入。",
            },
            {
              findingId: "finding_secondary",
              severity: "P2",
              title: "缺少错误映射",
              body: "错误类型没有映射到稳定响应。",
              path: "src/main.rs",
              layer: "unstaged",
              startLine: 2,
              endLine: 2,
              side: "current",
              evidence: "响应直接拼接异常文本。",
            },
          ],
        };
        return { threadId: "thread_demo", turnId, status: "completed" as const, report };
      }),
      readHistoryPage: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 17,
        turns: [],
        items: [],
        nextCursor: null,
      })),
    };
    const controllerRef: {
      current?: {
        readonly aiReview: AiReviewController;
        readonly review: ReviewController;
        readonly setSnapshotRevision: (revision: string) => void;
      };
    } = {};
    let resolveRepair!: (accepted: { turnId: string; queued: boolean }) => void;
    const pendingRepair = new Promise<{ turnId: string; queued: boolean }>((resolve) => {
      resolveRepair = resolve;
    });
    const onRepairFinding = vi.fn<NonNullable<ReviewPanelViewProps["onRepairFinding"]>>(
      async () => ({ turnId: "turn_fix_default", queued: false }),
    );
    onRepairFinding.mockImplementationOnce(() => pendingRepair);
    render(
      <AiReviewPanelHarness
        controllerRef={controllerRef}
        gateway={gateway}
        onRepairFinding={onRepairFinding}
      />,
    );

    const startReview = await screen.findByRole("button", { name: "AI 审查" });
    expect(startReview).toBeEnabled();
    await user.click(startReview);
    await waitFor(() => expect(gateway.startAiReview).toHaveBeenCalledOnce());
    const firstRunId = controllerRef.current!.aiReview.selectedRun!.runId;
    await act(async () => controllerRef.current!.aiReview.actions.refresh(firstRunId));
    expect(
      await screen.findByText("基于冻结工件 frozen_git_revision_1 的审查报告。"),
    ).toBeVisible();
    expect(screen.getByText("审查结果")).toBeVisible();
    expect(screen.queryByText("尚无审查报告。")).not.toBeInTheDocument();

    expect(screen.getByText("src/main.rs:1-1 · 未暂存")).toBeVisible();
    expect(screen.queryByText(/src\/main\.rs:1-1 · unstaged/u)).not.toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: "定位" })[0]!);
    expect(controllerRef.current!.review.actions.selectFile).toHaveBeenCalledWith(file.fileId);
    expect(screen.getAllByRole("button", { name: "定位" })[0]!).toBeVisible();

    await user.click(screen.getAllByRole("button", { name: "修复" })[0]!);
    await waitFor(() => expect(onRepairFinding).toHaveBeenCalledOnce());
    expect(onRepairFinding).toHaveBeenCalledWith(
      expect.stringContaining("冻结快照：rev_000000000000000000000001"),
      expect.objectContaining({ findingId: "finding_shared" }),
      expect.objectContaining({ reportId: "report_turn_review_1" }),
    );
    const firstFinding = screen.getByText("缺少边界校验").closest("li");
    expect(firstFinding).not.toBeNull();
    expect(within(firstFinding!).getByRole("button", { name: "修复" })).toHaveAttribute(
      "aria-busy",
      "true",
    );

    act(() => controllerRef.current!.setSnapshotRevision("git_revision_2"));
    expect(await screen.findByText(/报告基于不同快照/u)).toBeVisible();
    expect(screen.queryByRole("button", { name: "修复" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重新审查" }));
    await waitFor(() => expect(gateway.startAiReview).toHaveBeenCalledTimes(2));
    const secondRunId = controllerRef.current!.aiReview.selectedRun!.runId;
    await act(async () => controllerRef.current!.aiReview.actions.refresh(secondRunId));
    expect(
      await screen.findByText("基于冻结工件 frozen_git_revision_2 的审查报告。"),
    ).toBeVisible();
    expect(screen.getByText("src/main.rs:1-1 · 未暂存")).toBeVisible();
    expect(screen.getAllByRole("button", { name: "修复" })[0]!).toBeEnabled();
    expect(screen.getAllByRole("button", { name: "修复" })[0]!).not.toHaveAttribute("aria-busy");
    expect(screen.queryByText("修复已提交")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    await act(async () => {
      resolveRepair({ turnId: "turn_fix", queued: false });
      await pendingRepair;
    });
    expect(screen.queryByText("修复已提交")).not.toBeInTheDocument();

    act(() => controllerRef.current!.aiReview.actions.select(firstRunId));
    expect(
      await screen.findByText("基于冻结工件 frozen_git_revision_1 的审查报告。"),
    ).toBeVisible();
    expect(await screen.findByText("修复已提交")).toBeVisible();
    expect(screen.queryByText(/turn_fix/u)).not.toBeInTheDocument();

    act(() => controllerRef.current!.aiReview.actions.select(secondRunId));
    expect(
      await screen.findByText("基于冻结工件 frozen_git_revision_2 的审查报告。"),
    ).toBeVisible();
    expect(screen.queryByText("修复已提交")).not.toBeInTheDocument();
    onRepairFinding.mockImplementationOnce(async () => {
      throw new Error("修复提交失败");
    });
    await user.click(screen.getAllByRole("button", { name: "修复" })[1]!);
    const secondReportFirstFinding = screen.getByText("缺少边界校验").closest("li");
    const secondFinding = screen.getByText("缺少错误映射").closest("li");
    expect(secondReportFirstFinding).not.toBeNull();
    expect(secondFinding).not.toBeNull();
    await waitFor(() =>
      expect(within(secondFinding!).getByRole("alert")).toHaveTextContent("修复提交失败，请重试。"),
    );
    expect(within(secondReportFirstFinding!).queryByRole("alert")).not.toBeInTheDocument();

    act(() => controllerRef.current!.aiReview.actions.select(firstRunId));
    expect(
      await screen.findByText("基于冻结工件 frozen_git_revision_1 的审查报告。"),
    ).toBeVisible();
    const firstReportSecondFinding = screen.getByText("缺少错误映射").closest("li");
    expect(firstReportSecondFinding).not.toBeNull();
    expect(within(firstReportSecondFinding!).queryByRole("alert")).not.toBeInTheDocument();

    expect(gateway.captureSnapshot).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ expectedSnapshotRevision: "git_revision_1" }),
    );
    expect(gateway.captureSnapshot).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ expectedSnapshotRevision: "git_revision_2" }),
    );
    act(() => controllerRef.current!.aiReview.actions.select(secondRunId));
    expect(
      await screen.findByText("基于冻结工件 frozen_git_revision_2 的审查报告。"),
    ).toBeVisible();
    expect(screen.getAllByRole("button", { name: "修复" })[0]!).toBeEnabled();
  });

  it("渲染权威 Diff，并把操作委托给 controller", async () => {
    const user = userEvent.setup();
    const controller = makeController();
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getAllByText("src/main.rs")[0]).toBeVisible();
    expect(screen.getByText("old→new")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "审查更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "暂存全部" }));
    expect(controller.actions.applyAction).toHaveBeenCalledWith("stage", { kind: "all" });
  });

  /** 真实 DOM 已提交但旧 passive effect 尚未运行时，点击不能被旧失败投影提前当作完成。 */
  it("旧失败渲染的 effect 不提前消费新重试意图", async () => {
    const failed = makeController({
      error: { code: "READ_FAILED", message: "读取失败，请重试。", retryable: true },
    });

    /** 用父 layout 阶段点击已提交的真实按钮，复现 WebView2 输入先于旧 passive effect 的顺序。 */
    function RetryBeforePassiveEffects({ controller }: { controller: ReviewController }) {
      useLayoutEffect(() => {
        screen.getByRole("button", { name: "读取失败，请重试。 重试" }).click();
      }, []);
      return <ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />;
    }

    const view = render(<RetryBeforePassiveEffects controller={failed} />);
    expect(failed.actions.refresh).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: "重新读取审阅" })).not.toHaveFocus();
    view.rerender(<RetryBeforePassiveEffects controller={makeController({ loading: true })} />);
    view.rerender(<RetryBeforePassiveEffects controller={makeController()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "重新读取审阅" })).toHaveFocus());
  });

  /** 快速读取可能合并掉 loading 渲染，回程依据失败投影改变，不强求可见忙碌阶段。 */
  it("无可见加载阶段的重试完成仍返回顶部刷新", async () => {
    const user = userEvent.setup();
    const failed = makeController({
      error: { code: "READ_FAILED", message: "读取失败，请重试。", retryable: true },
    });
    const view = render(<ReviewPanelView viewModel={failed.viewModel} actions={failed.actions} />);
    await user.click(screen.getByRole("button", { name: "读取失败，请重试。 重试" }));
    const settled = makeController();
    view.rerender(<ReviewPanelView viewModel={settled.viewModel} actions={failed.actions} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "重新读取审阅" })).toHaveFocus());
  });

  /** 重试提示在加载开始后卸载，完成时必须返回仍存在的刷新控件以保留键盘操作位置。 */
  it("错误提示重试完成后将焦点返回顶部刷新按钮", async () => {
    const user = userEvent.setup();
    const controller = makeController({
      error: { code: "READ_FAILED", message: "读取失败，请重试。", retryable: true },
    });
    const view = render(
      <ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />,
    );
    await user.click(screen.getByRole("button", { name: "读取失败，请重试。 重试" }));
    expect(controller.actions.refresh).toHaveBeenCalledOnce();
    const refreshing = makeController({ loading: true });
    view.rerender(
      <ReviewPanelView viewModel={refreshing.viewModel} actions={controller.actions} />,
    );
    expect(
      screen.queryByRole("button", { name: "读取失败，请重试。 重试" }),
    ).not.toBeInTheDocument();
    const settled = makeController();
    view.rerender(<ReviewPanelView viewModel={settled.viewModel} actions={controller.actions} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "重新读取审阅" })).toHaveFocus());
  });

  /** 读取期间用户已操作别的控件时，完成反馈不能抢走新的键盘位置。 */
  it("错误重试完成后保留用户主动移动的焦点", async () => {
    const user = userEvent.setup();
    const controller = makeController({
      error: { code: "READ_FAILED", message: "读取失败，请重试。", retryable: true },
    });
    const view = render(
      <ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />,
    );
    await user.click(screen.getByRole("button", { name: "读取失败，请重试。 重试" }));
    const refreshing = makeController({ loading: true });
    view.rerender(
      <ReviewPanelView viewModel={refreshing.viewModel} actions={controller.actions} />,
    );
    const otherControl = screen.getByRole("button", { name: "审查更多操作" });
    otherControl.focus();
    const settled = makeController();
    view.rerender(<ReviewPanelView viewModel={settled.viewModel} actions={controller.actions} />);
    expect(otherControl).toHaveFocus();
  });

  /** 焦点意图随原读取范围失效，不能跨 Git 来源或 Workspace 延迟回流。 */
  it.each(["来源", "工作区"] as const)("错误重试期间切换%s后不返回旧焦点", async (scope) => {
    const user = userEvent.setup();
    const controller = makeController({
      error: { code: "READ_FAILED", message: "读取失败，请重试。", retryable: true },
    });
    const view = render(
      <ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />,
    );
    await user.click(screen.getByRole("button", { name: "读取失败，请重试。 重试" }));
    const refreshing = makeController({ loading: true });
    view.rerender(
      <ReviewPanelView viewModel={refreshing.viewModel} actions={controller.actions} />,
    );
    const changed = makeController(
      scope === "来源"
        ? { source: { kind: "branch", refId: "main" } }
        : { catalog: { ...controller.viewModel.state.catalog!, workspaceId: "ws_other" } },
    );
    view.rerender(<ReviewPanelView viewModel={changed.viewModel} actions={controller.actions} />);
    expect(screen.getByRole("button", { name: "重新读取审阅" })).not.toHaveFocus();
  });

  /** 隐藏的会话保留投影但不取得焦点，避免异步完成干扰当前工作面。 */
  it("隐藏审阅面板的错误重试完成后不取得焦点", async () => {
    const user = userEvent.setup();
    const controller = makeController({
      error: { code: "READ_FAILED", message: "读取失败，请重试。", retryable: true },
    });
    const view = render(
      <div>
        <ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />
      </div>,
    );
    await user.click(screen.getByRole("button", { name: "读取失败，请重试。 重试" }));
    const refreshing = makeController({ loading: true });
    view.rerender(
      <div hidden>
        <ReviewPanelView viewModel={refreshing.viewModel} actions={controller.actions} />
      </div>,
    );
    const settled = makeController();
    view.rerender(
      <div hidden>
        <ReviewPanelView viewModel={settled.viewModel} actions={controller.actions} />
      </div>,
    );
    expect(screen.getByRole("button", { name: "重新读取审阅", hidden: true })).not.toHaveFocus();
  });

  /** 被动刷新没有用户重试意图，即使焦点在页面上也不自动跳入审阅区。 */
  it("被动刷新完成后不主动取得焦点", () => {
    const refreshing = makeController({ loading: true });
    const view = render(
      <ReviewPanelView viewModel={refreshing.viewModel} actions={refreshing.actions} />,
    );
    const settled = makeController();
    view.rerender(<ReviewPanelView viewModel={settled.viewModel} actions={refreshing.actions} />);
    expect(screen.getByRole("button", { name: "重新读取审阅" })).not.toHaveFocus();
  });

  /** 冲突文件的组合 Diff 解析错误需要用户先处理冲突；普通解析错误仍保留原提示。 */
  it("为冲突文件显示解决后重读说明且保留显式刷新", async () => {
    const user = userEvent.setup();
    const conflictFile: ReviewFile = { ...file, status: "conflicted" };
    const parseFailure = { code: "PARSE", message: "Git 输出解析失败", retryable: false };
    const conflictedController = makeController({
      snapshot: { ...snapshot, files: [conflictFile] },
      diffError: parseFailure,
    });
    const view = render(
      <ReviewPanelView
        viewModel={conflictedController.viewModel}
        actions={conflictedController.actions}
      />,
    );

    expect(
      screen.getByText("此文件有合并冲突，暂不支持显示组合 Diff。请解决冲突后重新读取。"),
    ).toBeVisible();
    expect(screen.queryByText("Git 输出解析失败")).not.toBeInTheDocument();
    expect(conflictedController.actions.refresh).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "重新读取" }));
    expect(conflictedController.actions.refresh).toHaveBeenCalledOnce();

    const ordinaryController = makeController({ diffError: parseFailure });
    view.rerender(
      <ReviewPanelView
        viewModel={ordinaryController.viewModel}
        actions={ordinaryController.actions}
      />,
    );
    expect(screen.getByText("Git 输出解析失败")).toBeVisible();
    expect(
      screen.queryByText("此文件有合并冲突，暂不支持显示组合 Diff。请解决冲突后重新读取。"),
    ).not.toBeInTheDocument();
  });

  /** 真实 Git 只给有界差异也必须允许切换布局，不能把缺少全文误判为不可用。 */
  it("没有完整前后正文也可双向切换差异布局", async () => {
    const user = userEvent.setup();
    const controller = makeController({
      diff: { ...diff, original: null, modified: null },
      viewMode: "unified",
    });
    const { rerender } = render(
      <ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />,
    );

    expect(screen.getByRole("region", { name: "统一 Diff src/main.rs" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "审查更多操作" }));
    const split = screen.getByRole("menuitemradio", { name: "双栏 Diff" });
    expect(split).toBeEnabled();
    await user.click(split);
    expect(controller.actions.setViewMode).toHaveBeenCalledWith("split");
    controller.viewModel.state.viewMode = "split";
    rerender(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);
    expect(screen.getByRole("region", { name: "双栏 Diff src/main.rs" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "审查更多操作" }));
    expect(screen.getByRole("menuitemradio", { name: "双栏 Diff" })).toHaveAttribute(
      "aria-checked",
      "true",
    );
    await user.click(screen.getByRole("menuitemradio", { name: "统一 Diff" }));
    expect(controller.actions.setViewMode).toHaveBeenLastCalledWith("unified");
    controller.viewModel.state.viewMode = "unified";
    rerender(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);
    expect(screen.getByRole("region", { name: "统一 Diff src/main.rs" })).toBeVisible();
    expect(controller.actions.refresh).not.toHaveBeenCalled();
    expect(controller.actions.selectFile).not.toHaveBeenCalled();
    expect(screen.queryByText("当前文件没有可用的安全 Diff。")).not.toBeInTheDocument();
  });

  /** 破坏性文件撤销必须经应用内确认，取消不会产生 mutation，确认只提交一次。 */
  it("用可访问确认 Dialog 收口文件撤销", async () => {
    const user = userEvent.setup();
    const controller = makeController();
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    await user.click(screen.getByRole("button", { name: "审查更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "撤销文件" }));
    const dialog = screen.getByRole("alertdialog", { name: "撤销文件变更？" });
    expect(dialog).toBeVisible();
    expect(screen.getByRole("button", { name: "取消" })).toHaveFocus();
    expect(controller.actions.applyAction).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "取消" }));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "审查更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "撤销文件" }));
    await user.click(screen.getByRole("button", { name: "确认撤销" }));
    expect(controller.actions.applyAction).toHaveBeenCalledTimes(1);
    expect(controller.actions.applyAction).toHaveBeenCalledWith("revert", {
      kind: "file",
      fileId: file.fileId,
    });
  });

  /** 右键操作必须使用指针下文件的 layer/id，不能先切换当前 Diff 或借用选中目标。 */
  it("按右键文件层显示操作并对原文件执行，不导航当前 Diff", async () => {
    const user = userEvent.setup();
    const stagedFile: ReviewFile = {
      ...file,
      fileId: "file_staged",
      path: "src/staged.rs",
      layer: "staged",
    };
    const uncommittedSource = { kind: "uncommitted" as const };
    const mixedSnapshot: ReviewSnapshot = {
      ...snapshot,
      source: uncommittedSource,
      files: [file, stagedFile],
      capabilities: { stage: true, unstage: true, revert: true },
    };
    const controller = makeController({
      source: uncommittedSource,
      snapshot: mixedSnapshot,
      selectedFileId: file.fileId,
      diff: { ...diff, source: uncommittedSource },
    });
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    const stagedRow = screen.getByRole("treeitem", { name: "查看 src/staged.rs 的已暂存变更" });
    fireEvent.contextMenu(stagedRow, { clientX: 28, clientY: 36 });
    await user.click(await screen.findByRole("menuitem", { name: "取消暂存文件" }));

    expect(controller.actions.applyAction).toHaveBeenCalledWith("unstage", {
      kind: "file",
      fileId: stagedFile.fileId,
    });
    expect(controller.actions.selectFile).not.toHaveBeenCalled();
    expect(screen.getByRole("treeitem", { name: "查看 src/main.rs 的未暂存变更" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });

  /** 右键撤销仍经过应用内确认，并保留被点中文件的稳定 identity。 */
  it("右键撤销通过确认后只提交目标文件", async () => {
    const user = userEvent.setup();
    const otherFile: ReviewFile = {
      ...file,
      fileId: "file_other",
      path: "src/other.rs",
    };
    const uncommittedSource = { kind: "uncommitted" as const };
    const mixedSnapshot: ReviewSnapshot = {
      ...snapshot,
      source: uncommittedSource,
      files: [file, otherFile],
      capabilities: { stage: true, unstage: true, revert: true },
    };
    const controller = makeController({
      source: uncommittedSource,
      snapshot: mixedSnapshot,
      selectedFileId: file.fileId,
      diff: { ...diff, source: uncommittedSource },
    });
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    fireEvent.contextMenu(
      screen.getByRole("treeitem", { name: "查看 src/other.rs 的未暂存变更" }),
      { clientX: 28, clientY: 36 },
    );
    await user.click(await screen.findByRole("menuitem", { name: "撤销文件" }));
    expect(screen.getByRole("alertdialog", { name: "撤销文件变更？" })).toBeVisible();
    await waitFor(() => expect(screen.getByRole("button", { name: "取消" })).toHaveFocus());
    expect(controller.actions.applyAction).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "确认撤销" }));

    expect(controller.actions.applyAction).toHaveBeenCalledWith("revert", {
      kind: "file",
      fileId: otherFile.fileId,
    });
    expect(controller.actions.selectFile).not.toHaveBeenCalled();
  });

  /** 菜单键与 Escape 共享同一上下文流程，菜单关闭后焦点回到原始文件行。 */
  it("支持 Shift+F10 并在 Escape 后恢复文件行焦点", async () => {
    const user = userEvent.setup();
    const controller = makeController();
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);
    const fileRow = screen.getByRole("treeitem", { name: "查看 src/main.rs 的未暂存变更" });

    fileRow.focus();
    fireEvent.keyDown(fileRow, { key: "F10", shiftKey: true });
    expect(await screen.findByRole("menuitem", { name: "暂存文件" })).toBeVisible();
    await user.keyboard("{Escape}");
    await waitFor(() => expect(fileRow).toHaveFocus());
    expect(controller.actions.applyAction).not.toHaveBeenCalled();
  });

  /** 快照刷新移除菜单目标时，关闭旧菜单且不把 action 迁移到其余或当前选中文件。 */
  it("右键菜单打开后目标消失会安全收起", async () => {
    const stagedFile: ReviewFile = {
      ...file,
      fileId: "file_staged",
      path: "src/staged.rs",
      layer: "staged",
    };
    const uncommittedSource = { kind: "uncommitted" as const };
    const mixedSnapshot: ReviewSnapshot = {
      ...snapshot,
      source: uncommittedSource,
      files: [file, stagedFile],
      capabilities: { stage: true, unstage: true, revert: true },
    };
    const controller = makeController({
      source: uncommittedSource,
      snapshot: mixedSnapshot,
      selectedFileId: file.fileId,
      diff: { ...diff, source: uncommittedSource },
    });
    const view = render(
      <ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />,
    );
    fireEvent.contextMenu(
      screen.getByRole("treeitem", { name: "查看 src/staged.rs 的已暂存变更" }),
      { clientX: 28, clientY: 36 },
    );
    expect(await screen.findByRole("menuitem", { name: "取消暂存文件" })).toBeVisible();

    const latestSnapshot = { ...mixedSnapshot, files: [file] };
    view.rerender(
      <ReviewPanelView
        viewModel={{
          ...controller.viewModel,
          state: { ...controller.viewModel.state, snapshot: latestSnapshot },
          visibleFiles: [file],
        }}
        actions={controller.actions}
      />,
    );

    await waitFor(() =>
      expect(screen.queryByRole("menuitem", { name: "取消暂存文件" })).toBeNull(),
    );
    expect(controller.actions.applyAction).not.toHaveBeenCalled();
    expect(controller.actions.selectFile).not.toHaveBeenCalled();
  });

  it("只读来源的更多菜单只保留阅读动作", async () => {
    const user = userEvent.setup();
    const branchSnapshot: ReviewSnapshot = {
      ...snapshot,
      source: { kind: "branch", refId: "main" },
      capabilities: { stage: false, unstage: false, revert: false },
    };
    const controller = makeController({
      source: branchSnapshot.source,
      snapshot: branchSnapshot,
      diff: { ...diff, source: branchSnapshot.source },
    });
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    await user.click(screen.getByRole("button", { name: "审查更多操作" }));
    expect(screen.getByRole("menuitemradio", { name: "双栏 Diff" })).toBeVisible();
    expect(screen.queryByRole("menuitem", { name: /暂存|撤销/ })).not.toBeInTheDocument();
  });

  it("无 Git 快照时显示明确不可用状态并停止文件区 loading", () => {
    const controller = makeController({
      loading: false,
      catalogLoading: false,
      snapshot: undefined,
      diff: undefined,
      selectedFileId: undefined,
      error: {
        code: "NOT_GIT_REPOSITORY",
        message: "当前目录不是 Git 工作区，审查不可用。",
        retryable: false,
      },
    });
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getByText("当前目录不是 Git 工作区，审查不可用。")).toBeVisible();
    expect(screen.getByText("文件列表尚未读取。")).toBeVisible();
    expect(screen.queryByLabelText("变更统计")).not.toBeInTheDocument();
    expect(screen.queryByText("正在读取文件…")).not.toBeInTheDocument();
  });

  /** snapshot 失败时保留原因和可用重读动作，但空投影不能冒充搜索无结果或零统计。 */
  it("限额失败时保留查询并只把原因放在顶部重读通知", async () => {
    const user = userEvent.setup();
    const limitMessage =
      "审阅内容超出当前安全上限。请切换到改动较少的审阅来源，再点顶部“重新读取审阅”按钮。";
    const controller = makeController({
      loading: false,
      catalogLoading: false,
      snapshot: undefined,
      diff: undefined,
      selectedFileId: undefined,
      query: "missing-file",
      error: { code: "REVIEW_LIMIT", message: limitMessage, retryable: false },
    });
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getByText(limitMessage)).toBeVisible();
    expect(screen.getByText("文件列表尚未读取。")).toBeVisible();
    expect(screen.queryByText("没有匹配的文件。")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("变更统计")).not.toBeInTheDocument();
    expect(screen.getByRole("searchbox", { name: "筛选文件" })).toHaveValue("missing-file");
    expect(screen.getByRole("combobox", { name: "审查来源" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "重新读取审阅" }));
    expect(controller.actions.refresh).toHaveBeenCalledOnce();
  });

  /** 加载期间不把暂时尚无 snapshot 的树状态渲染成空结果或零统计。 */
  it("snapshot 加载时显示加载状态且隐藏统计", () => {
    const controller = makeController({
      loading: true,
      snapshot: undefined,
      diff: undefined,
      selectedFileId: undefined,
      query: "missing-file",
    });
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getByText("正在读取文件…")).toBeVisible();
    expect(screen.queryByText("没有匹配的文件。")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("变更统计")).not.toBeInTheDocument();
  });

  /** 只有成功返回的空 snapshot 才展示真实零统计。 */
  it("真实空 snapshot 保留零变更统计", () => {
    const emptySnapshot: ReviewSnapshot = {
      ...snapshot,
      files: [],
      stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
    };
    const controller = makeController({
      snapshot: emptySnapshot,
      diff: undefined,
      selectedFileId: undefined,
      query: "",
    });
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getByLabelText("变更统计")).toHaveTextContent("0 文件+0-0");
    expect(screen.getByText("当前范围没有变更。")).toBeVisible();
    expect(screen.queryByText("文件列表尚未读取。")).not.toBeInTheDocument();
  });

  /** Git 纯重命名保留状态中文并说明零正文差异，不显示空 Diff 编辑器。 */
  it("纯重命名显示中文状态和无正文差异说明", () => {
    const renamedFile = {
      ...file,
      fileId: "file_renamed",
      path: "src/new.rs",
      oldPath: "src/old.rs",
      status: "renamed" as const,
      additions: 0,
      deletions: 0,
      hunks: [],
    };
    const renamedDiff: ReviewFileDiff = {
      ...diff,
      fileId: renamedFile.fileId,
      path: renamedFile.path,
      oldPath: renamedFile.oldPath,
      status: "renamed",
      original: "same content",
      modified: "same content",
      unified: null,
      hunks: [],
      lines: [],
    };
    const controller = makeController({
      snapshot: { ...snapshot, files: [renamedFile] },
      selectedFileId: renamedFile.fileId,
      diff: renamedDiff,
    });
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getByText("重命名")).toBeVisible();
    expect(screen.getByText("仅重命名，无正文差异。")).toBeVisible();
    expect(screen.queryByText("renamed", { exact: true })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "只读 Diff src/new.rs" })).not.toBeInTheDocument();
  });

  /** 已读取快照后的搜索无匹配仍保留真实统计并明确报告筛选结果。 */
  it("已读取 snapshot 的筛选无匹配状态保持不变", () => {
    const controller = makeController({ query: "missing-file" });
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getByText("没有匹配的文件。")).toBeVisible();
    expect(screen.getByLabelText("变更统计")).toHaveTextContent("1 文件");
    expect(screen.queryByText("文件列表尚未读取。")).not.toBeInTheDocument();
  });

  /** 仅含元数据的二进制条目可选择但不读取正文，详情区必须解释真实原因。 */
  it("二进制文件短路正文读取时显示准确状态", () => {
    const binaryFile = { ...file, binary: true };
    const controller = makeController({
      snapshot: { ...snapshot, files: [binaryFile] },
      diff: undefined,
      selectedFileId: binaryFile.fileId,
      diffLoading: false,
    });
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getByText("二进制文件不提供文本 Diff。")).toBeVisible();
    expect(screen.queryByText("选择文件查看 Diff。")).not.toBeInTheDocument();
  });

  /** 独立视图的比较来源经过统一 Radix 选择器；未提交层筛选由共享范围菜单承载。 */
  it("通过统一选择器切换比较来源", async () => {
    const user = userEvent.setup();
    const controller = makeController();
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    screen.getByRole("combobox", { name: "审查来源" }).focus();
    await user.keyboard("{Enter}{ArrowDown}{Enter}");
    expect(controller.actions.setSource).toHaveBeenCalledWith({ kind: "branch", refId: "main" });
  });

  it("按审阅容器宽度进入窄栏，并在返回文件树后恢复所选文件焦点", async () => {
    const user = userEvent.setup();
    stubReviewWidth(520);
    const controller = makeController();
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);
    const shell = screen.getByRole("region", { name: "审阅" });
    await waitFor(() => expect(shell).toHaveAttribute("data-review-layout", "narrow"));

    const fileRow = screen.getByRole("treeitem", { name: "查看 src/main.rs 的未暂存变更" });
    expect(fileRow).toHaveAttribute("tabindex", "0");
    await user.click(fileRow);
    expect(shell).toHaveClass("is-detail-open");
    await user.click(screen.getByRole("button", { name: "返回变更文件" }));

    await waitFor(() => expect(fileRow).toHaveFocus());
    expect(shell).not.toHaveClass("is-detail-open");
  });
});
