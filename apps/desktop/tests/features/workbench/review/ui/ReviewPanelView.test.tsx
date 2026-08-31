// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
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

vi.mock("@/features/workbench/editor", () => ({
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

/** 固定 Review 的 760px 响应式事实，让测试只验证抽屉可访问性而不伪造布局像素。 */
function stubReviewDrawerMode(matches: boolean): void {
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => ({
      matches: query === "(max-width: 760px)" ? matches : false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(() => false),
    })),
  );
}

const file: ReviewFile = {
  fileId: "file_main",
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
    await user.click(screen.getByRole("button", { name: "暂存全部" }));
    expect(controller.actions.applyAction).toHaveBeenCalledWith("stage", { kind: "all" });
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

    expect(screen.queryByRole("button", { name: "暂存全部" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "撤销全部" })).not.toBeInTheDocument();
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

  /** Review 的来源和过滤器必须经过同一 Radix 选择器，不再依赖 WebView2 原生菜单。 */
  it("通过统一选择器切换审查来源和变更过滤器", async () => {
    const user = userEvent.setup();
    const controller = makeController();
    render(<ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    screen.getByRole("combobox", { name: "审查来源" }).focus();
    await user.keyboard("{Enter}{ArrowDown}{Enter}");
    expect(controller.actions.setSource).toHaveBeenCalledWith({ kind: "branch", refId: "main" });

    screen.getByRole("combobox", { name: "变更类型" }).focus();
    await user.keyboard("{Enter}{ArrowDown}{Enter}");
    expect(controller.actions.setFilter).toHaveBeenCalledWith("added");
  });

  it("窄屏关闭态移除抽屉焦点，打开时恢复交互并在再次关闭后归还焦点", async () => {
    const user = userEvent.setup();
    stubReviewDrawerMode(true);
    const initial = makeController();
    const rendered = render(
      <ReviewPanelView viewModel={initial.viewModel} actions={initial.actions} />,
    );
    const trigger = screen.getByRole("button", { name: "打开文件列表" });
    const hiddenFiles = screen.getByLabelText("变更文件列表", { selector: "aside" });
    const hiddenSearch = screen.getByPlaceholderText("筛选文件…");
    expect(hiddenFiles).toHaveAttribute("aria-hidden", "true");
    expect(hiddenFiles).toHaveAttribute("inert");
    trigger.focus();
    await user.tab();
    expect(hiddenSearch).not.toHaveFocus();

    const open = makeController({ drawerOpen: true });
    rendered.rerender(<ReviewPanelView viewModel={open.viewModel} actions={open.actions} />);
    const openFiles = screen.getByRole("complementary", { name: "变更文件列表" });
    const openSearch = screen.getByRole("searchbox", { name: "筛选文件" });
    expect(openFiles).not.toHaveAttribute("aria-hidden");
    expect(openFiles).not.toHaveAttribute("inert");
    openSearch.focus();
    expect(openSearch).toHaveFocus();

    const closed = makeController({ drawerOpen: false });
    rendered.rerender(<ReviewPanelView viewModel={closed.viewModel} actions={closed.actions} />);

    await waitFor(() => expect(trigger).toHaveFocus());
    expect(screen.getByLabelText("变更文件列表", { selector: "aside" })).toHaveAttribute("inert");
  });
});
