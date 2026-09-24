// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  FilesWorkspace,
  type FilesActions,
  type FilesViewModel,
  type OpenDocument,
} from "@/features/workbench/files";

afterEach(cleanup);

/**
 * 构造没有 native 端口的纯视图模型，确保 UI 测试不会通过 operations 间接创建
 * controller；覆盖字段集中在这里也能让契约新增时显式暴露，而不是静默补默认值。
 */
function createViewModel(overrides: Partial<FilesViewModel> = {}): FilesViewModel {
  return {
    nodes: [],
    treeLoading: false,
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
    selectNode: vi.fn(),
    toggleDirectory: vi.fn(),
    retryTree: vi.fn(),
    refreshTree: vi.fn(),
    changeSearchQuery: vi.fn(),
    openSearchResult: vi.fn(),
    openPath: vi.fn(async () => true),
    openExternalDocument: vi.fn(() => true),
    selectDocument: vi.fn(),
    closeDocument: vi.fn(),
    beginSaveAs: vi.fn(),
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

  it("空 projection 在右侧呈现可搜索资源树，编辑器不再引用左侧位置", () => {
    render(<FilesWorkspace viewModel={createViewModel()} actions={createActions()} />);

    expect(
      screen.getByLabelText("文件工作区").querySelector(".ja-files-workspace-body"),
    ).toHaveClass("is-browser-only");
    expect(screen.getByText("工作区没有可显示的文件")).toBeVisible();
    expect(screen.getByRole("searchbox", { name: "搜索工作区" })).toHaveAttribute(
      "placeholder",
      "筛选文件…",
    );
    expect(screen.getByText("从右侧文件树选择文件开始编辑。")).toBeVisible();
  });

  it("工具栏右侧只统计当前权威树已载入的普通文件", () => {
    render(
      <FilesWorkspace
        viewModel={createViewModel({
          nodes: [
            {
              id: "directory:src",
              name: "src",
              path: "src",
              kind: "directory",
              children: [
                { id: "file:src/main.ts", name: "main.ts", path: "src/main.ts", kind: "file" },
              ],
            },
            { id: "file:README.md", name: "README.md", path: "README.md", kind: "file" },
          ],
        })}
        actions={createActions()}
      />,
    );

    expect(screen.getByText("2 个文件")).toBeVisible();
  });

  it("marks an explicitly opened external snapshot as a read-only Files tab", () => {
    const path = String.raw`C:\Users\person\notes.txt`;
    const externalDocument: OpenDocument = {
      path,
      content: "external snapshot",
      savedContent: "external snapshot",
      revision: {
        kind: "file",
        size: 17,
        modifiedUnixMillis: null,
        sha256: null,
      },
      encoding: "utf8",
      newline: "lf",
      kind: "text",
      readOnly: true,
      readOnlyReason: "工作区外文件，只读",
      externalFile: true,
      truncated: true,
      status: "clean",
      draftGeneration: 0,
    };
    render(
      <FilesWorkspace
        viewModel={createViewModel({
          documents: { [path]: externalDocument },
          openPaths: [path],
          activePath: path,
          activeDocument: externalDocument,
        })}
        actions={createActions()}
      />,
    );

    const externalTab = document.querySelector<HTMLElement>('[data-external-file="true"]');
    const editor = document.querySelector<HTMLElement>(".ja-files-editor-content");
    expect(externalTab).toHaveAttribute("data-file-tab-path", path);
    expect(editor).toHaveAttribute("data-document-path", path);
    expect(editor).toHaveAttribute("data-document-read-only", "true");
    expect(editor).toHaveAttribute("data-document-truncated", "true");
    expect(screen.getByText("工作区外文件，只读")).toBeVisible();
  });

  /** 右键保存必须绑定非活动的被点中文档，而不能通过选择标签改变当前编辑目标。 */
  it("从非活动文件标签右键保存对应路径", async () => {
    const path = "src/draft.ts";
    const activePath = "README.md";
    const dirtyDocument: OpenDocument = {
      path,
      content: "draft",
      savedContent: "old",
      revision: { kind: "file", size: 3, modifiedUnixMillis: null, sha256: null },
      encoding: "utf8",
      newline: "lf",
      kind: "text",
      readOnly: false,
      status: "dirty",
      draftGeneration: 1,
    };
    const activeDocument: OpenDocument = {
      ...dirtyDocument,
      path: activePath,
      content: "readme",
      savedContent: "readme",
      status: "clean",
      draftGeneration: 0,
    };
    const actions = createActions();
    render(
      <FilesWorkspace
        viewModel={createViewModel({
          documents: { [path]: dirtyDocument, [activePath]: activeDocument },
          openPaths: [path, activePath],
          activePath,
          activeDocument,
        })}
        actions={actions}
      />,
    );

    const tab = [...document.querySelectorAll<HTMLElement>("[data-file-tab-path]")].find(
      (element) => element.dataset["fileTabPath"] === path,
    );
    expect(tab).toBeDefined();
    fireEvent.contextMenu(tab!, { clientX: 40, clientY: 60 });
    fireEvent.click(await screen.findByRole("menuitem", { name: "保存" }));

    expect(actions.saveDocument).toHaveBeenCalledWith(path);
    expect(actions.selectDocument).not.toHaveBeenCalled();
  });

  /** 冲突标签只暴露 controller 已实现的另存为入口，并使用右键目标路径。 */
  it("冲突文件标签右键另存为当前目标", async () => {
    const path = "src/conflict.ts";
    const conflictDocument: OpenDocument = {
      path,
      content: "local",
      savedContent: "old",
      revision: { kind: "file", size: 3, modifiedUnixMillis: null, sha256: null },
      encoding: "utf8",
      newline: "lf",
      kind: "text",
      readOnly: false,
      status: "conflict",
      draftGeneration: 1,
    };
    const actions = createActions();
    render(
      <FilesWorkspace
        viewModel={createViewModel({
          documents: { [path]: conflictDocument },
          openPaths: [path],
          activePath: path,
          activeDocument: conflictDocument,
        })}
        actions={actions}
      />,
    );

    const tab = screen.getByRole("tab", { name: /conflict\.ts/ });
    fireEvent.contextMenu(tab, { clientX: 40, clientY: 60 });
    fireEvent.click(await screen.findByRole("menuitem", { name: "另存为" }));

    expect(actions.beginSaveAs).toHaveBeenCalledWith(path);
    expect(actions.selectDocument).not.toHaveBeenCalled();
  });

  /** 菜单关闭仍经过 closeDocument，控制器投影出的未保存确认 Dialog 保持原流程。 */
  it("右键关闭未保存标签继续显示草稿确认", async () => {
    const path = "src/draft.ts";
    const dirtyDocument: OpenDocument = {
      path,
      content: "draft",
      savedContent: "old",
      revision: { kind: "file", size: 3, modifiedUnixMillis: null, sha256: null },
      encoding: "utf8",
      newline: "lf",
      kind: "text",
      readOnly: false,
      status: "dirty",
      draftGeneration: 1,
    };
    const actions = createActions();
    const viewModel = createViewModel({
      documents: { [path]: dirtyDocument },
      openPaths: [path],
      activePath: path,
      activeDocument: dirtyDocument,
    });
    const view = render(<FilesWorkspace viewModel={viewModel} actions={actions} />);

    const tab = screen.getByRole("tab", { name: /draft\.ts/ });
    fireEvent.contextMenu(tab, { clientX: 40, clientY: 60 });
    fireEvent.click(await screen.findByRole("menuitem", { name: "关闭" }));
    expect(actions.closeDocument).toHaveBeenCalledWith(path);

    view.rerender(
      <FilesWorkspace
        viewModel={{
          ...viewModel,
          closeDocumentRequest: { path },
          closeRequestedDocument: dirtyDocument,
        }}
        actions={actions}
      />,
    );
    expect(screen.getByRole("alertdialog", { name: "关闭未保存文件" })).toBeVisible();
    await waitFor(() => expect(screen.getByRole("button", { name: "取消" })).toHaveFocus());
    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(actions.dismissCloseDocument).toHaveBeenCalledOnce();
  });

  /** 连续右键另一个标签必须替换菜单 generation，旧菜单项不能作用到旧路径。 */
  it("右键另一个文件标签会按新目标重新定位菜单", async () => {
    const firstPath = "src/draft.ts";
    const secondPath = "README.md";
    const firstDocument: OpenDocument = {
      path: firstPath,
      content: "draft",
      savedContent: "old",
      revision: { kind: "file", size: 3, modifiedUnixMillis: null, sha256: null },
      encoding: "utf8",
      newline: "lf",
      kind: "text",
      readOnly: false,
      status: "dirty",
      draftGeneration: 1,
    };
    const secondDocument: OpenDocument = {
      ...firstDocument,
      path: secondPath,
      content: "readme",
      savedContent: "readme",
      status: "clean",
      draftGeneration: 0,
    };
    const actions = createActions();
    render(
      <FilesWorkspace
        viewModel={createViewModel({
          documents: { [firstPath]: firstDocument, [secondPath]: secondDocument },
          openPaths: [firstPath, secondPath],
          activePath: firstPath,
          activeDocument: firstDocument,
        })}
        actions={actions}
      />,
    );

    const tabNodes = [...document.querySelectorAll<HTMLElement>("[data-file-tab-path]")];
    const firstTab = tabNodes.find((element) => element.dataset["fileTabPath"] === firstPath);
    const secondTab = tabNodes.find((element) => element.dataset["fileTabPath"] === secondPath);
    expect(firstTab).toBeDefined();
    expect(secondTab).toBeDefined();
    fireEvent.contextMenu(firstTab!, { clientX: 40, clientY: 60 });
    expect(await screen.findByRole("menuitem", { name: "保存" })).toBeVisible();
    fireEvent.contextMenu(secondTab!, { clientX: 72, clientY: 92 });
    expect(await screen.findByRole("menuitem", { name: "关闭" })).toBeVisible();
    expect(screen.queryByRole("menuitem", { name: "保存" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("menuitem", { name: "关闭" }));

    expect(actions.closeDocument).toHaveBeenCalledWith(secondPath);
    expect(actions.closeDocument).not.toHaveBeenCalledWith(firstPath);
    expect(actions.saveDocument).not.toHaveBeenCalled();
  });

  /** 键盘菜单键打开文件标签菜单，Escape 将焦点恢复到原标签而不切换文档。 */
  it("支持文件标签 ContextMenu 键并恢复焦点", async () => {
    const user = userEvent.setup();
    const path = "README.md";
    const documentModel: OpenDocument = {
      path,
      content: "readme",
      savedContent: "readme",
      revision: { kind: "file", size: 3, modifiedUnixMillis: null, sha256: null },
      encoding: "utf8",
      newline: "lf",
      kind: "text",
      readOnly: false,
      status: "clean",
      draftGeneration: 0,
    };
    const actions = createActions();
    render(
      <FilesWorkspace
        viewModel={createViewModel({
          documents: { [path]: documentModel },
          openPaths: [path],
          activePath: path,
          activeDocument: documentModel,
        })}
        actions={actions}
      />,
    );
    const tab = screen.getByRole("tab", { name: /README\.md/ });
    tab.focus();
    fireEvent.keyDown(tab, { key: "ContextMenu" });
    expect(await screen.findByRole("menuitem", { name: "关闭" })).toBeVisible();
    await user.keyboard("{Escape}");

    await waitFor(() => expect(tab).toHaveFocus());
    expect(actions.selectDocument).not.toHaveBeenCalled();
  });
});
