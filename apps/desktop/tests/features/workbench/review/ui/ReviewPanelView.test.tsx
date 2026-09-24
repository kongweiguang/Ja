// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  ReviewController,
  ReviewControllerState,
} from "@/features/workbench/review/application/useReviewController";
import type {
  ReviewFile,
  ReviewFileDiff,
  ReviewSnapshot,
} from "@/features/workbench/review/domain/types";
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

describe("ReviewPanelView", () => {
  it("渲染权威 Diff，并把操作委托给 controller", async () => {
    const user = userEvent.setup();
    const controller = makeController();
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getAllByText("src/main.rs")[0]).toBeVisible();
    expect(screen.getByText("old→new")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "变更操作" }));
    await user.click(screen.getByRole("menuitem", { name: "暂存全部" }));
    expect(controller.actions.applyAction).toHaveBeenCalledWith("stage", { kind: "all" });
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
    const split = screen.getByRole("button", { name: "双栏 Diff" });
    expect(split).toBeEnabled();
    await user.click(split);
    expect(controller.actions.setViewMode).toHaveBeenCalledWith("split");
    controller.viewModel.state.viewMode = "split";
    rerender(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);
    expect(screen.getByRole("region", { name: "双栏 Diff src/main.rs" })).toBeVisible();
    expect(split).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "统一 Diff" }));
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

    await user.click(screen.getByRole("button", { name: "变更操作" }));
    await user.click(screen.getByRole("menuitem", { name: "撤销文件" }));
    const dialog = screen.getByRole("alertdialog", { name: "撤销文件变更？" });
    expect(dialog).toBeVisible();
    expect(screen.getByRole("button", { name: "取消" })).toHaveFocus();
    expect(controller.actions.applyAction).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "取消" }));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "变更操作" }));
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

  it("只读来源不渲染 mutation 控件", () => {
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

    expect(screen.queryByRole("button", { name: "变更操作" })).not.toBeInTheDocument();
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
    expect(screen.getByText("没有可审查的文件。")).toBeVisible();
    expect(screen.queryByText("正在读取文件…")).not.toBeInTheDocument();
  });

  /** metadata-only 二进制条目可选择但不读取正文，详情区必须解释真实原因。 */
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
