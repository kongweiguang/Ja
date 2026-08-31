// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FileTree, type WorkspaceFileNode } from "@/features/workbench/files";

const originalElementFromPoint = Object.getOwnPropertyDescriptor(document, "elementFromPoint");

/** 构造最小文件 projection，测试只验证拖拽适配器而不伪造 native revision。 */
function file(path: string): WorkspaceFileNode {
  return { id: `file:${path}`, name: path.split("/").pop() ?? path, path, kind: "file" };
}

/** 构造可见目录 projection，并显式保留子项用于覆盖与层级校验。 */
function directory(path: string, children: readonly WorkspaceFileNode[] = []): WorkspaceFileNode {
  return {
    id: `directory:${path}`,
    name: path.split("/").pop() ?? path,
    path,
    kind: "directory",
    children,
  };
}

/** 把命中测试固定到一个 DOM 目标，避免依赖 jsdom 不存在的真实布局。 */
function pointAt(element: Element): void {
  Object.defineProperty(document, "elementFromPoint", {
    configurable: true,
    value: vi.fn(() => element),
  });
}

/**
 * 在单个源行上模拟 WebView pointer capture，并记录释放状态，确保测试能区分正常
 * capture 生命周期和 document 监听兼容路径。
 */
function installPointerCapture(element: HTMLElement): {
  set: ReturnType<typeof vi.fn>;
  release: ReturnType<typeof vi.fn>;
} {
  const captured = new Set<number>();
  const set = vi.fn((pointerId: number) => {
    captured.add(pointerId);
  });
  const release = vi.fn((pointerId: number) => {
    captured.delete(pointerId);
  });
  Object.defineProperties(element, {
    setPointerCapture: { configurable: true, value: set },
    releasePointerCapture: { configurable: true, value: release },
    hasPointerCapture: {
      configurable: true,
      value: (pointerId: number) => captured.has(pointerId),
    },
  });
  return { set, release };
}

/**
 * jsdom 没有原生 PointerEvent，因此在 MouseEvent 上补齐 pointerId；这样 React 与
 * document 原生监听器收到的坐标和身份与 WebView 一致，不会产生伪阳性。
 */
function dispatchPointer(
  target: Element | Window | Document,
  type: string,
  pointerId: number,
  clientX: number,
  clientY: number,
): void {
  const event = new MouseEvent(type, {
    bubbles: true,
    cancelable: true,
    button: 0,
    clientX,
    clientY,
  });
  Object.defineProperty(event, "pointerId", { configurable: true, value: pointerId });
  fireEvent(target, event);
}

/** 开始一次超过阈值的 pointer 拖拽，但把最终提交或取消留给具体测试。 */
function beginDrag(source: HTMLElement, target: Element, pointerId: number): void {
  pointAt(target);
  dispatchPointer(source, "pointerdown", pointerId, 2, 2);
  dispatchPointer(document, "pointermove", pointerId, 20, 20);
}

/** 构造带 pointerId 的 capture 丢失事件，贴近 WebView 实际派发契约。 */
function dispatchLostPointerCapture(source: HTMLElement, pointerId: number): void {
  const event = new Event("lostpointercapture", { bubbles: true });
  Object.defineProperty(event, "pointerId", { configurable: true, value: pointerId });
  fireEvent(source, event);
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  if (originalElementFromPoint === undefined) Reflect.deleteProperty(document, "elementFromPoint");
  else Object.defineProperty(document, "elementFromPoint", originalElementFromPoint);
});

/**
 * 把虚拟列表 viewport 固定为可验证几何；测试只观察传给 Arborist 的宽高，不伪造
 * scrollHeight，从而避免把 jsdom 的零布局当成真实滚动证据。
 */
function installViewportGeometry(width: number, height: number): void {
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (
    this: HTMLElement,
  ) {
    const isViewport = this.classList.contains("ja-file-tree-viewport");
    const resolvedWidth = isViewport ? width : 160;
    const resolvedHeight = isViewport ? height : 30;
    return {
      x: 0,
      y: 0,
      top: 0,
      right: resolvedWidth,
      bottom: resolvedHeight,
      left: 0,
      width: resolvedWidth,
      height: resolvedHeight,
      toJSON: () => ({}),
    };
  });
}

describe("FileTree pointer move", () => {
  it("keeps disclosure SVG presses out of row pointer capture", () => {
    const onMove = vi.fn();
    render(<FileTree nodes={[directory("src", [file("src/child.ts")])]} onMove={onMove} />);
    const disclosure = screen.getByRole("button", { name: "展开src" });
    const disclosureIcon = disclosure.querySelector("svg")!;
    const sourceRow = screen.getByText("src").closest<HTMLElement>("[data-path]")!;
    const capture = installPointerCapture(sourceRow);

    dispatchPointer(disclosureIcon, "pointerdown", 5, 8, 8);
    fireEvent.click(disclosureIcon);

    expect(capture.set).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "折叠src" })).toBeVisible();
    expect(screen.getByText("child.ts")).toBeVisible();
    expect(onMove).not.toHaveBeenCalled();
  });

  it("only commits a nested item to the explicit workspace-root drop zone on pointerup", () => {
    const source = file("src/child.ts");
    const onMove = vi.fn();
    render(<FileTree nodes={[directory("src", [source])]} onMove={onMove} />);
    fireEvent.click(screen.getByRole("button", { name: "展开src" }));
    const sourceRow = screen.getByText("child.ts").closest<HTMLElement>("[data-path]")!;
    const rootDropZone = screen.getByRole("group", { name: "工作区根目录拖放区" });
    const capture = installPointerCapture(sourceRow);

    beginDrag(sourceRow, rootDropZone, 7);
    expect(rootDropZone).toHaveClass("is-drag-target");
    expect(capture.set).toHaveBeenCalledWith(7);
    expect(onMove).not.toHaveBeenCalled();

    dispatchPointer(document, "pointerup", 7, 20, 20);
    expect(onMove).toHaveBeenCalledOnce();
    expect(onMove).toHaveBeenCalledWith(source, "");
    expect(capture.release).toHaveBeenCalledWith(7);
    expect(rootDropZone).not.toHaveClass("is-drag-target");
  });

  it("treats pointercancel as zero-write cleanup even after a valid target is active", () => {
    const source = file("main.ts");
    const target = directory("src");
    const onMove = vi.fn();
    render(<FileTree nodes={[source, target]} onMove={onMove} />);
    const sourceRow = screen.getByText("main.ts").closest<HTMLElement>("[data-path]")!;
    const targetRow = screen.getByText("src").closest<HTMLElement>("[data-path]")!;
    const capture = installPointerCapture(sourceRow);

    beginDrag(sourceRow, targetRow, 11);
    expect(screen.getByText("src").closest("[data-path]")).toHaveClass("is-drag-target");
    dispatchPointer(document, "pointercancel", 11, 20, 20);
    dispatchPointer(document, "pointerup", 11, 20, 20);

    expect(onMove).not.toHaveBeenCalled();
    expect(capture.release).toHaveBeenCalledWith(11);
    expect(screen.getByText("src").closest("[data-path]")).not.toHaveClass("is-drag-target");
  });

  it("cancels on window blur and lost pointer capture without consuming a later pointerup", () => {
    const source = file("main.ts");
    const target = directory("src");
    const onMove = vi.fn();
    render(<FileTree nodes={[source, target]} onMove={onMove} />);
    const sourceRow = screen.getByText("main.ts").closest<HTMLElement>("[data-path]")!;
    const targetRow = screen.getByText("src").closest<HTMLElement>("[data-path]")!;
    installPointerCapture(sourceRow);

    beginDrag(sourceRow, targetRow, 13);
    fireEvent.blur(window);
    dispatchPointer(document, "pointerup", 13, 20, 20);
    expect(onMove).not.toHaveBeenCalled();

    beginDrag(sourceRow, targetRow, 17);
    dispatchLostPointerCapture(sourceRow, 17);
    dispatchPointer(document, "pointerup", 17, 20, 20);
    expect(onMove).not.toHaveBeenCalled();
    expect(screen.getByText("src").closest("[data-path]")).not.toHaveClass("is-drag-target");
  });

  it("rejects root collisions, same-parent no-ops and unsafe projection paths before typed move", () => {
    const nestedReadme = file("src/README.md");
    const rootReadme = file("README.md");
    const unsafe = file("../escape.ts");
    const onMove = vi.fn();
    render(
      <FileTree nodes={[directory("src", [nestedReadme]), rootReadme, unsafe]} onMove={onMove} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "展开src" }));
    const rootDropZone = screen.getByTestId("file-tree-root-drop-zone");

    const nestedRow = screen
      .getByText("README.md", { selector: '[title="src/README.md"]' })
      .closest<HTMLElement>("[data-path]")!;
    installPointerCapture(nestedRow);
    beginDrag(nestedRow, rootDropZone, 19);
    dispatchPointer(document, "pointerup", 19, 20, 20);

    const rootRow = screen
      .getByText("README.md", { selector: '[title="README.md"]' })
      .closest<HTMLElement>("[data-path]")!;
    installPointerCapture(rootRow);
    beginDrag(rootRow, rootDropZone, 23);
    dispatchPointer(document, "pointerup", 23, 20, 20);

    const unsafeRow = screen.getByText("escape.ts").closest<HTMLElement>("[data-path]")!;
    const unsafeCapture = installPointerCapture(unsafeRow);
    beginDrag(unsafeRow, rootDropZone, 29);
    dispatchPointer(document, "pointerup", 29, 20, 20);

    expect(onMove).not.toHaveBeenCalled();
    expect(unsafeCapture.set).not.toHaveBeenCalled();
  });
});

describe("FileTree production explorer interactions", () => {
  it("uses the measured viewport height instead of a fixed business height", async () => {
    installViewportGeometry(312, 684);

    render(<FileTree nodes={[file("main.ts")]} />);

    const tree = await screen.findByRole("tree", { name: "工作区文件" });
    await waitFor(() => {
      expect(tree).toHaveStyle({ width: "312px", height: "684px" });
    });
    expect(screen.getByTestId("file-tree")).not.toHaveAttribute("style");
  });

  it("keeps special filesystem nodes visible but removes file mutation and open actions", async () => {
    const onRename = vi.fn();
    const onMove = vi.fn();
    const onTrash = vi.fn();
    const onRefresh = vi.fn();
    const onOpenTarget = vi.fn();
    const linked: WorkspaceFileNode = {
      id: "symlink:linked",
      name: "linked",
      path: "linked",
      kind: "symlink",
    };
    render(
      <FileTree
        nodes={[
          linked,
          { id: "reparse:junction", name: "junction", path: "junction", kind: "reparse_point" },
          { id: "other:device", name: "device", path: "device", kind: "other" },
        ]}
        selectedPath="linked"
        onRename={onRename}
        onMove={onMove}
        onTrash={onTrash}
        onRefresh={onRefresh}
        openTargets={[{ target: "file_explorer", displayName: "文件资源管理器" }]}
        onOpenTarget={onOpenTarget}
      />,
    );

    expect(screen.getByText("链接")).toBeVisible();
    expect(screen.getByText("重解析")).toBeVisible();
    expect(screen.getByText("特殊")).toBeVisible();
    fireEvent.contextMenu(screen.getByText("linked"));
    const menu = await screen.findByRole("menu", { name: "linked 文件操作" });
    expect(within(menu).getAllByRole("menuitem")).toHaveLength(1);
    expect(within(menu).getByRole("menuitem", { name: /刷新此目录/ })).toBeVisible();
    expect(within(menu).queryByRole("menuitem", { name: /重命名/ })).not.toBeInTheDocument();
    expect(within(menu).queryByRole("menuitem", { name: /移入回收站/ })).not.toBeInTheDocument();
    expect(within(menu).queryByRole("menuitem", { name: /资源管理器/ })).not.toBeInTheDocument();

    fireEvent.keyDown(menu, { key: "Escape" });
    const tree = screen.getByRole("tree", { name: "工作区文件" });
    fireEvent.keyDown(tree, { key: "F2" });
    fireEvent.keyDown(tree, { key: "Delete" });
    const linkedRow = screen.getByText("linked").closest<HTMLElement>("[data-path]")!;
    const capture = installPointerCapture(linkedRow);
    beginDrag(linkedRow, screen.getByTestId("file-tree-root-drop-zone"), 37);
    dispatchPointer(document, "pointerup", 37, 20, 20);

    expect(capture.set).not.toHaveBeenCalled();
    expect(onRename).not.toHaveBeenCalled();
    expect(onMove).not.toHaveBeenCalled();
    expect(onTrash).not.toHaveBeenCalled();
    expect(onOpenTarget).not.toHaveBeenCalled();
  });

  it("opens a real workspace-root menu from blank space and creates in the root", async () => {
    const onCreateDirectory = vi.fn();
    const onRename = vi.fn();
    const onTrash = vi.fn();
    render(
      <FileTree
        nodes={[file("main.ts")]}
        onCreateDirectory={onCreateDirectory}
        onRename={onRename}
        onTrash={onTrash}
      />,
    );

    fireEvent.contextMenu(screen.getByTestId("file-tree-viewport"), {
      clientX: 20,
      clientY: 24,
    });
    const menu = await screen.findByRole("menu", { name: "工作区根目录操作" });
    expect(within(menu).getByRole("menuitem", { name: "新建目录" })).toHaveFocus();
    expect(within(menu).queryByRole("menuitem", { name: /重命名/ })).not.toBeInTheDocument();
    expect(within(menu).queryByRole("menuitem", { name: /移入回收站/ })).not.toBeInTheDocument();

    fireEvent.click(within(menu).getByRole("menuitem", { name: "新建目录" }));
    const input = screen.getByRole("textbox", { name: "新建目录名" });
    fireEvent.change(input, { target: { value: "docs" } });
    fireEvent.keyDown(input, { key: "Enter" });

    expect(onCreateDirectory).toHaveBeenCalledOnce();
    expect(onCreateDirectory).toHaveBeenCalledWith("", "docs");
  });

  it("keeps Escape zero-write for create and rename even when blur follows unmount", () => {
    const node = file("main.ts");
    const onCreateFile = vi.fn();
    const onRename = vi.fn();
    render(
      <FileTree
        nodes={[node]}
        selectedPath={node.path}
        onCreateFile={onCreateFile}
        onRename={onRename}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "新建文件" }));
    const createInput = screen.getByRole("textbox", { name: "新建文件名" });
    fireEvent.change(createInput, { target: { value: "cancelled.ts" } });
    fireEvent.keyDown(createInput, { key: "Escape" });
    fireEvent.blur(createInput);

    const tree = screen.getByRole("tree", { name: "工作区文件" });
    fireEvent.keyDown(tree, { key: "F2" });
    const renameInput = screen.getByRole("textbox", { name: "重命名 main.ts" });
    fireEvent.change(renameInput, { target: { value: "cancelled-name.ts" } });
    fireEvent.keyDown(renameInput, { key: "Escape" });
    fireEvent.blur(renameInput);

    expect(onCreateFile).not.toHaveBeenCalled();
    expect(onRename).not.toHaveBeenCalled();
  });

  it("supports keyboard menu, rename, trash and refresh with focus recovery", async () => {
    const user = userEvent.setup();
    const node = file("main.ts");
    const onRename = vi.fn();
    const onTrash = vi.fn();
    const onRefresh = vi.fn();
    render(
      <FileTree
        nodes={[node]}
        selectedPath={node.path}
        onRename={onRename}
        onTrash={onTrash}
        onRefresh={onRefresh}
      />,
    );
    const tree = screen.getByRole("tree", { name: "工作区文件" });
    tree.focus();

    fireEvent.keyDown(tree, { key: "F10", shiftKey: true });
    const menu = await screen.findByRole("menu", { name: "main.ts 文件操作" });
    const firstItem = within(menu).getByRole("menuitem", { name: /重命名/ });
    expect(firstItem).toHaveFocus();
    fireEvent.keyDown(firstItem, { key: "End" });
    expect(within(menu).getByRole("menuitem", { name: /刷新此目录/ })).toHaveFocus();
    fireEvent.keyDown(document.activeElement ?? menu, { key: "Escape" });
    await waitFor(() => expect(screen.getByRole("treeitem", { name: "main.ts" })).toHaveFocus());

    fireEvent.keyDown(tree, { key: "Delete" });
    expect(onTrash).toHaveBeenCalledWith(node);
    fireEvent.keyDown(tree, { key: "F5" });
    expect(onRefresh).toHaveBeenCalledWith("");

    fireEvent.keyDown(tree, { key: "F2" });
    const input = screen.getByRole("textbox", { name: "重命名 main.ts" });
    await user.clear(input);
    await user.type(input, "index.ts");
    await user.keyboard("{Enter}");
    await waitFor(() => expect(onRename).toHaveBeenCalledOnce());
    expect(onRename).toHaveBeenCalledWith(node, "index.ts");
  });
});
