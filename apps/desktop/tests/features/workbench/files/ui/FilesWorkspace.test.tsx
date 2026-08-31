// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FilesWorkspace, type FilesActions, type FilesViewModel } from "@/features/workbench/files";

afterEach(cleanup);

/**
 * 构造没有 native 端口的纯视图模型，确保 UI 测试不会通过 operations 间接创建
 * controller；覆盖字段集中在这里也能让契约新增时显式暴露，而不是静默补默认值。
 */
function createViewModel(overrides: Partial<FilesViewModel> = {}): FilesViewModel {
  return {
    nodes: [],
    treeLoading: false,
    mode: "files",
    searchQuery: "",
    searchResults: [],
    searchLoading: false,
    documents: {},
    openPaths: [],
    lifecycleClosing: false,
    mutationRecoveryRequired: false,
    openTargets: [],
    ...overrides,
  };
}

/**
 * 构造只记录语义 intent 的 action 集合；测试刻意不提供 Tauri adapter 或 workspace
 * identity，用类型约束证明纯视图无法越层执行文件副作用。
 */
function createActions(): FilesActions {
  return {
    showFiles: vi.fn(),
    selectNode: vi.fn(),
    toggleDirectory: vi.fn(),
    retryTree: vi.fn(),
    refreshTree: vi.fn(),
    changeSearchQuery: vi.fn(),
    openSearchResult: vi.fn(),
    selectDocument: vi.fn(),
    closeDocument: vi.fn(),
    compareConflict: vi.fn(),
    reloadConflict: vi.fn(),
    retrySave: vi.fn(),
    editDocument: vi.fn(),
    saveDocument: vi.fn(async () => undefined),
    hideComparison: vi.fn(),
    changeSaveAsTarget: vi.fn(),
    cancelSaveAs: vi.fn(),
    submitSaveAs: vi.fn(async () => undefined),
    dismissCloseDocument: vi.fn(),
    discardCloseDocument: vi.fn(),
    cancelTrash: vi.fn(),
    confirmTrash: vi.fn(async () => undefined),
  };
}

describe("FilesWorkspaceView", () => {
  it("直接使用 controller projection 渲染 loading primitive", () => {
    render(
      <FilesWorkspace
        viewModel={createViewModel({ treeLoading: true })}
        actions={createActions()}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("正在读取文件树");
  });

  it("把脱敏错误交给 error primitive 并只发出重试 intent", () => {
    const actions = createActions();
    render(
      <FilesWorkspace
        viewModel={createViewModel({ treeError: "文件树暂时不可用" })}
        actions={actions}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("文件树暂时不可用");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(actions.retryTree).toHaveBeenCalledOnce();
  });

  it("空 projection 同时呈现资源树和编辑器的空状态", () => {
    render(<FilesWorkspace viewModel={createViewModel()} actions={createActions()} />);

    expect(
      screen.getByLabelText("文件工作区").querySelector(".ja-files-workspace-body"),
    ).toHaveClass("is-browser-only");
    expect(screen.getByText("工作区没有可显示的文件")).toBeVisible();
    expect(screen.getByText("从左侧文件树选择文件开始编辑。")).toBeVisible();
  });
});
