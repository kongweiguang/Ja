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

/** 显式路径定位只展开必要祖先，忽略目录可在 Rust 定向 page 加载后正常成为选择项。 */
describe("FileTree directory reveal", () => {
  /** @author kongweiguang：等待受控 selection effect 生效后再断言定位结果。 */
  it("opens ancestors, selects, and scrolls to the requested directory", async () => {
    installViewportGeometry(400, 240);
    const target = directory("src/nested", [file("src/nested/readme.md")]);
    const nodes = [directory("src", [target])];
    const { rerender } = render(<FileTree nodes={nodes} />);
    rerender(
      <FileTree
        nodes={nodes}
        selectedPath={target.path}
        revealRequest={{ path: target.path, sequence: 1 }}
      />,
    );

    const row = await screen.findByText("nested");
    expect(row).toBeVisible();
    await waitFor(() =>
      expect(row.closest('[role="treeitem"]')).toHaveAttribute("aria-selected", "true"),
    );
    expect(row.closest('[role="treeitem"]')).toHaveAttribute("aria-level", "2");
  });
});

describe("FileTree production explorer interactions", () => {
  /**
   * @author kongweiguang
   * 用真实键盘在中间插入文本，约束虚拟行重绘不得替换输入节点或重置用户光标。
   */
  it("keeps inline rename focus and caret while typing into the middle of a name", async () => {
    installViewportGeometry(400, 240);
    const user = userEvent.setup();
    const node = file("main.ts");
    render(<FileTree nodes={[node]} selectedPath={node.path} onRename={vi.fn()} />);

    const tree = await screen.findByRole("tree", { name: "工作区文件" });
    fireEvent.keyDown(tree, { key: "F2" });
    const input = screen.getByRole("textbox", { name: "重命名 main.ts" });
    expect(input).toHaveFocus();

    await user.keyboard("{ArrowLeft}{ArrowLeft}{ArrowLeft}");
    await user.keyboard("-copy");

    const currentInput = screen.getByRole("textbox", { name: "重命名 main.ts" });
    expect(currentInput).toBe(input);
    expect(currentInput).toHaveFocus();
    expect(currentInput).toHaveValue("main-copy.ts");
    expect((currentInput as HTMLInputElement).selectionStart).toBe("main-copy".length);
  });

  /**
   * @author kongweiguang
   * 原生组合态 Enter 可能用于确认输入法候选；只允许 composition 结束后的 Enter 提交文件名。
   */
  it("does not commit a Chinese inline rename while native IME composition is active", () => {
    installViewportGeometry(400, 240);
    const node = file("main.ts");
    const onRename = vi.fn();
    render(<FileTree nodes={[node]} selectedPath={node.path} onRename={onRename} />);

    const tree = screen.getByRole("tree", { name: "工作区文件" });
    fireEvent.keyDown(tree, { key: "F2" });
    const input = screen.getByRole("textbox", { name: "重命名 main.ts" });
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: "新文件名" } });
    fireEvent.keyDown(input, { key: "Enter", isComposing: true });

    expect(screen.getByRole("textbox", { name: "重命名 main.ts" })).toBe(input);
    expect(input).toHaveFocus();
    expect(input).toHaveValue("新文件名");
    expect(onRename).not.toHaveBeenCalled();

    fireEvent.compositionEnd(input, { data: "名" });
    fireEvent.keyDown(input, { key: "Enter", isComposing: false });
    expect(onRename).toHaveBeenCalledTimes(1);
    expect(onRename).toHaveBeenCalledWith(node, "新文件名");
  });

  /**
   * @author kongweiguang
   * 原生组合态 Escape 可能取消候选而非文件 Rename；保留草稿直到 composition 结束再确认。
   */
  it("does not cancel a Chinese inline rename when Escape is used during native IME composition", () => {
    installViewportGeometry(400, 240);
    const node = file("main.ts");
    const onRename = vi.fn();
    render(<FileTree nodes={[node]} selectedPath={node.path} onRename={onRename} />);

    const tree = screen.getByRole("tree", { name: "工作区文件" });
    fireEvent.keyDown(tree, { key: "F2" });
    const input = screen.getByRole("textbox", { name: "重命名 main.ts" });
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: "新文件名" } });
    fireEvent.keyDown(input, { key: "Escape", isComposing: true });

    expect(screen.getByRole("textbox", { name: "重命名 main.ts" })).toBe(input);
    expect(input).toHaveFocus();
    expect(input).toHaveValue("新文件名");
    expect(onRename).not.toHaveBeenCalled();

    fireEvent.compositionEnd(input, { data: "名" });
    fireEvent.keyDown(input, { key: "Enter", isComposing: false });
    expect(onRename).toHaveBeenCalledTimes(1);
    expect(onRename).toHaveBeenCalledWith(node, "新文件名");
  });

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

  /** 空白区仍沿用 Radix 菜单的焦点漫游，再调用 root controller 的新建动作。 */
  it("opens a real workspace-root menu from blank space and creates in the root", async () => {
    const user = userEvent.setup();
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
    expect(menu).toHaveFocus();
    await user.keyboard("{ArrowDown}");
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

  /** 常用资源管理器保持一步可达；其余已发现应用集中展示并保持原目标路径。 */
  it("groups additional open targets without hiding the direct explorer action", async () => {
    const user = userEvent.setup();
    const onOpenTarget = vi.fn();
    render(
      <FileTree
        nodes={[file("main.ts")]}
        openTargets={[
          { target: "vscode", displayName: "VS Code" },
          { target: "file_explorer", displayName: "文件资源管理器" },
          { target: "zed", displayName: "Zed" },
        ]}
        onOpenTarget={onOpenTarget}
      />,
    );

    fireEvent.contextMenu(screen.getByText("main.ts"));
    const menu = await screen.findByRole("menu", { name: "main.ts 文件操作" });
    expect(within(menu).getAllByRole("menuitem")).toHaveLength(2);
    fireEvent.click(within(menu).getByRole("menuitem", { name: /文件资源管理器/ }));
    expect(onOpenTarget).toHaveBeenCalledWith("file_explorer", "main.ts");
    await waitFor(() => expect(menu).not.toBeInTheDocument());

    fireEvent.contextMenu(screen.getByText("main.ts"));
    const reopened = await screen.findByRole("menu", { name: "main.ts 文件操作" });
    await user.click(within(reopened).getByRole("menuitem", { name: "使用其他应用打开" }));
    const submenu = await screen.findByRole("menu", { name: "使用其他应用打开" });
    expect(within(submenu).getAllByRole("menuitem")).toHaveLength(2);
    fireEvent.click(within(submenu).getByRole("menuitem", { name: /VS Code/ }));
    expect(onOpenTarget).toHaveBeenLastCalledWith("vscode", "main.ts");
  });

  /** 仅有回收站能力时不显示孤立分隔线，能力裁剪后菜单仍保持紧凑。 */
  it("omits a leading separator when trash is the only file action", async () => {
    render(<FileTree nodes={[file("main.ts")]} onTrash={vi.fn()} />);

    fireEvent.contextMenu(screen.getByText("main.ts"));
    const menu = await screen.findByRole("menu", { name: "main.ts 文件操作" });
    expect(within(menu).getAllByRole("menuitem")).toHaveLength(1);
    expect(within(menu).queryByRole("separator")).not.toBeInTheDocument();
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
    expect(menu).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(firstItem).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(within(menu).getByRole("menuitem", { name: /移入回收站/ })).toHaveFocus();
    await user.keyboard("{Escape}");
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

  it("adds ordinary files and directories to the current conversation without reading them", async () => {
    const user = userEvent.setup();
    const node = directory("src", [file("src/main.ts")]);
    const onAddToConversation = vi.fn();
    render(
      <FileTree
        nodes={[node]}
        selectedPath={node.path}
        onAddToConversation={onAddToConversation}
      />,
    );
    const tree = screen.getByRole("tree", { name: "工作区文件" });
    tree.focus();

    fireEvent.keyDown(tree, { key: "F10", shiftKey: true });
    await user.click(screen.getByRole("menuitem", { name: "添加到对话" }));
    expect(onAddToConversation).toHaveBeenCalledWith(node);
  });

  /** 列表刷新可能替换节点对象；菜单动作按 id 解析后应使用最新路径与名称。 */
  it("resolves a FileTree context target from the current stable node id", async () => {
    const user = userEvent.setup();
    const original = file("main.ts");
    const current = { ...file("renamed.ts"), id: original.id };
    const { rerender } = render(<FileTree nodes={[original]} onRename={vi.fn()} />);

    fireEvent.contextMenu(screen.getByText("main.ts"), { clientX: 24, clientY: 32 });
    expect(await screen.findByRole("menu", { name: "main.ts 文件操作" })).toBeVisible();
    rerender(<FileTree nodes={[current]} onRename={vi.fn()} />);
    expect(await screen.findByRole("menu", { name: "renamed.ts 文件操作" })).toBeVisible();

    await user.click(screen.getByRole("menuitem", { name: /重命名/ }));
    expect(screen.getByRole("textbox", { name: "重命名 renamed.ts" })).toBeInTheDocument();
  });

  /** 若刷新删除目标节点，菜单必须关闭，不能对失效 projection 执行副作用。 */
  it("closes the FileTree context menu when its stable node id disappears", async () => {
    const node = file("main.ts");
    const onTrash = vi.fn();
    const { rerender } = render(<FileTree nodes={[node]} onTrash={onTrash} />);

    fireEvent.contextMenu(screen.getByText("main.ts"), { clientX: 24, clientY: 32 });
    expect(await screen.findByRole("menu", { name: "main.ts 文件操作" })).toBeVisible();
    rerender(<FileTree nodes={[]} onTrash={onTrash} />);

    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(onTrash).not.toHaveBeenCalled();
  });

  /** 快速切换目标会重新锚定菜单，并让动作落到最后一次右击的文件。 */
  it("retargets an open FileTree menu to the latest right-clicked node", async () => {
    const user = userEvent.setup();
    const first = file("first.ts");
    const second = file("second.ts");
    const onTrash = vi.fn();
    render(<FileTree nodes={[first, second]} onTrash={onTrash} />);

    fireEvent.contextMenu(screen.getByText("first.ts"), { clientX: 18, clientY: 22 });
    expect(await screen.findByRole("menu", { name: "first.ts 文件操作" })).toBeVisible();
    fireEvent.contextMenu(screen.getByText("second.ts"), { clientX: 42, clientY: 58 });
    expect(await screen.findByRole("menu", { name: "second.ts 文件操作" })).toBeVisible();
    expect(document.querySelector(".ja-pointer-context-anchor")).toHaveStyle({
      left: "42px",
      top: "58px",
    });

    await user.click(screen.getByRole("menuitem", { name: /移入回收站/ }));
    expect(onTrash).toHaveBeenCalledWith(second);
  });
});
