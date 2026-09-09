// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import type { FilesWorkspaceLifecycle } from "@/features/workbench/files";
import type {
  FileReadDto,
  FileRevision,
  FileSaveResult,
  FilesWorkspaceOperations,
  TrashPrepareResult,
  WatchSubscription,
  WorkspaceChangedEvent,
  WorkspaceNativeDropEvent,
  WorkspaceTreePageDto,
} from "@/features/workbench/files";
import { FilesWorkspaceHarness as FilesWorkspace } from "./FilesWorkspaceHarness";

vi.mock("@/features/workbench/editor", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/features/workbench/editor")>();
  return {
    ...actual,
    /** 通过 editor 公共入口替换重型 CodeMirror，保持测试遵守跨 feature index 边界。 */
    CodeEditor: ({
      filePath,
      content,
      readOnly,
      onChange,
      onSave,
      onBlur,
    }: {
      filePath: string;
      content: string;
      readOnly?: boolean;
      onChange?: (content: string) => void;
      onSave?: () => void | Promise<void>;
      onBlur?: () => void;
    }) => (
      <textarea
        aria-label={`编辑文件 ${filePath}`}
        value={content}
        readOnly={readOnly}
        onChange={(event) => onChange?.(event.target.value)}
        onKeyDown={(event) => {
          if (event.ctrlKey && event.key.toLowerCase() === "s") {
            event.preventDefault();
            void onSave?.();
          }
        }}
        onBlur={() => onBlur?.()}
      />
    ),
  };
});

/** 为真实 MergeView 补齐无布局环境下的 Range 合同，使冲突测试不依赖浏览器几何。 */
beforeAll(() => {
  Object.defineProperty(Range.prototype, "getClientRects", {
    configurable: true,
    value: () => [] as unknown as DOMRectList,
  });
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

/** 构造完整 revision，使测试覆盖与 native IPC 相同的 CAS 合同。 */
function revision(kind: FileRevision["kind"], sha256: string, size = 10): FileRevision {
  return { kind, size, modifiedUnixMillis: null, sha256 };
}

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
}

/** 创建无需延时等待的事件栅栏，让生命周期竞态保持确定性。 */
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

/**
 * jsdom 没有完整 PointerEvent，实现时在 MouseEvent 上补齐 pointerId，确保回归
 * 测试穿过真实 FileTree 手势边界，而不是直接调用 Files mutation callback。
 */
function dispatchPointer(
  target: Element | Document,
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
  target.dispatchEvent(event);
}

/** 创建一组可控 operation graph，统一驱动编辑器、Watcher 与 mutation 测试。 */
function createOperations(): {
  operations: FilesWorkspaceOperations;
  emit: (event: WorkspaceChangedEvent) => void;
  emitFocus: (focused: boolean) => void;
  emitDrop: (event: WorkspaceNativeDropEvent) => void;
  resolveSave: (result?: FileSaveResult) => void;
} {
  let emit: (event: WorkspaceChangedEvent) => void = () => undefined;
  let emitFocus: (focused: boolean) => void = () => undefined;
  let emitDrop: (event: WorkspaceNativeDropEvent) => void = () => undefined;
  let resolveSave: (result?: FileSaveResult) => void = () => undefined;
  const operations: FilesWorkspaceOperations = {
    tree: vi.fn(
      async ({ relativePath }): Promise<WorkspaceTreePageDto> =>
        relativePath.length === 0
          ? {
              entries: [
                {
                  name: "main.ts",
                  relativePath: "main.ts",
                  kind: "file",
                  revision: revision("file", "r1"),
                },
                {
                  name: "src",
                  relativePath: "src",
                  kind: "directory",
                  revision: revision("directory", "d1"),
                  hasChildren: true,
                },
              ],
              directoryRevision: revision("directory", "root"),
            }
          : {
              entries: [
                {
                  name: "child.ts",
                  relativePath: "src/child.ts",
                  kind: "file",
                  revision: revision("file", "r2"),
                },
              ],
              directoryRevision: revision("directory", "d1"),
            },
    ),
    readFile: vi.fn(async ({ relativePath }) => ({
      path: relativePath,
      kind: "text" as const,
      content: relativePath.endsWith("child.ts")
        ? "export const child = true;"
        : "export const main = true;",
      revision: relativePath.endsWith("child.ts") ? revision("file", "r2") : revision("file", "r1"),
      encoding: "utf8" as const,
      newline: "lf" as const,
    })),
    saveFile: vi.fn(
      (input) =>
        new Promise<FileSaveResult>((resolve) => {
          resolveSave = (result) =>
            resolve(
              result ?? {
                revision: revision("file", input.mutationId + "-r"),
                mutationId: input.mutationId,
              },
            );
        }),
    ),
    createEntry: vi.fn(async () => ({ revision: revision("file", "new-r") })),
    moveEntry: vi.fn(async () => ({ revision: revision("file", "move-r") })),
    trashPrepare: vi.fn(async () => ({
      operationToken: "trash-1",
      fileCount: 1,
      totalBytes: 10,
      expiresAtUnixMillis: Date.now() + 30_000,
    })),
    trashCommit: vi.fn(async () => ({ revision: null })),
    importDrop: vi.fn(async () => undefined),
    search: vi.fn(async () => ({
      hits: [
        {
          id: "hit-1",
          path: "main.ts",
          line: 2,
          column: 4,
          preview: "export const main = true;",
          matchStart: 7,
          matchLength: 4,
        },
      ],
      truncated: false,
      scannedEntries: 1,
      skippedFiles: 0,
    })),
    watchStart: vi.fn(async (_input, listener) => {
      emit = listener;
      return { stop: vi.fn(async () => undefined) };
    }),
    watchRescan: vi.fn(async () => undefined),
    watchStop: vi.fn(async () => undefined),
    subscribeWindowFocus: vi.fn(async (listener) => {
      emitFocus = listener;
      return vi.fn(async () => undefined);
    }),
    subscribeNativeDrop: vi.fn(async (listener) => {
      emitDrop = listener;
      return vi.fn(async () => undefined);
    }),
  };
  return {
    operations,
    emit: (event) => emit(event),
    emitFocus: (focused) => emitFocus(focused),
    emitDrop: (event) => emitDrop(event),
    resolveSave: (result) => resolveSave(result),
  };
}

describe("FilesWorkspace", () => {
  it("keeps the Explorer selection aligned when an existing editor tab becomes active", async () => {
    const { operations } = createOperations();
    const user = userEvent.setup();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await user.click(await screen.findByText("main.ts"));
    await user.click(screen.getByRole("button", { name: "展开src" }));
    await user.click(await screen.findByText("child.ts"));
    expect(screen.getByRole("tab", { name: "child.ts" })).toHaveAttribute("aria-selected", "true");

    await user.click(screen.getByRole("tab", { name: "main.ts" }));

    expect(screen.getByRole("tab", { name: "main.ts" })).toHaveAttribute("aria-selected", "true");
    await waitFor(() =>
      expect(screen.getByRole("treeitem", { name: "main.ts" })).toHaveAttribute(
        "aria-selected",
        "true",
      ),
    );
  });

  /** 验证新打开和重新选择的文件可见，且不依赖 flex 项重叠形成偶然布局。 */
  it("reveals the active file tab inside the horizontal strip", async () => {
    const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "scrollIntoView");
    const scrollIntoView = vi.fn();
    Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
      configurable: true,
      value: scrollIntoView,
    });
    try {
      const { operations } = createOperations();
      const user = userEvent.setup();
      render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
      await user.click(await screen.findByText("main.ts"));
      await user.click(screen.getByRole("button", { name: "展开src" }));
      scrollIntoView.mockClear();

      await user.click(await screen.findByText("child.ts"));

      await waitFor(() =>
        expect(scrollIntoView).toHaveBeenCalledWith({ block: "nearest", inline: "nearest" }),
      );
      expect(scrollIntoView.mock.contexts.at(-1)).toBe(
        screen.getByRole("tab", { name: "child.ts" }).closest(".ja-files-editor-tab"),
      );
    } finally {
      if (original === undefined)
        delete (HTMLElement.prototype as { scrollIntoView?: unknown }).scrollIntoView;
      else Object.defineProperty(HTMLElement.prototype, "scrollIntoView", original);
    }
  });

  it("opens multiple documents and locates a search result", async () => {
    const { operations } = createOperations();
    const user = userEvent.setup();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    await user.click(screen.getByText("main.ts"));
    await screen.findByRole("tab", { name: "main.ts" });
    await user.type(screen.getByRole("searchbox", { name: "搜索工作区" }), "main");
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /^main\.ts/ })).toBeInTheDocument(),
    );
    const resultButton = screen.getByRole("button", { name: /^main\.ts/ });
    fireEvent.click(resultButton);
    await waitFor(() =>
      expect(screen.getByRole("tab", { name: /main\.ts/ })).toHaveAttribute(
        "aria-selected",
        "true",
      ),
    );
    expect(await screen.findByRole("textbox", { name: "编辑文件 main.ts" })).toHaveValue(
      "export const main = true;",
    );
  });

  it("keeps concurrent directory refreshes independent", async () => {
    const { operations, emit } = createOperations();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    const srcPage = deferred<WorkspaceTreePageDto>();
    const rootPage = deferred<WorkspaceTreePageDto>();
    vi.mocked(operations.tree).mockImplementation(({ relativePath }) =>
      relativePath === "src" ? srcPage.promise : rootPage.promise,
    );

    fireEvent.click(screen.getByRole("button", { name: "展开src" }));
    await waitFor(() =>
      expect(operations.tree).toHaveBeenCalledWith(
        expect.objectContaining({ relativePath: "src" }),
      ),
    );
    emit({
      relativePath: "main.ts",
      generation: 5,
      revision: revision("file", "tree-refresh"),
      requiresRescan: false,
    });
    await waitFor(() =>
      expect(
        vi.mocked(operations.tree).mock.calls.filter(([input]) => input.relativePath === ""),
      ).toHaveLength(2),
    );

    await act(async () => {
      rootPage.resolve({
        entries: [
          {
            name: "main.ts",
            relativePath: "main.ts",
            kind: "file",
            revision: revision("file", "tree-refresh"),
          },
          {
            name: "src",
            relativePath: "src",
            kind: "directory",
            revision: revision("directory", "src-refresh"),
            hasChildren: true,
          },
        ],
        directoryRevision: revision("directory", "root-refresh"),
      });
      srcPage.resolve({
        entries: [
          {
            name: "child.ts",
            relativePath: "src/child.ts",
            kind: "file",
            revision: revision("file", "child-refresh"),
          },
        ],
        directoryRevision: revision("directory", "src-refresh"),
      });
      await Promise.resolve();
    });

    const srcCalls = vi
      .mocked(operations.tree)
      .mock.calls.filter(([input]) => input.relativePath === "src").length;
    await screen.findByText("child.ts");
    expect(screen.getByRole("button", { name: "折叠src" })).toBeVisible();
    expect(
      vi.mocked(operations.tree).mock.calls.filter(([input]) => input.relativePath === "src"),
    ).toHaveLength(srcCalls);
  });

  it("keeps an expanded move target visible when a later root watcher refresh completes", async () => {
    const { operations, emit } = createOperations();
    const lateRootPage = deferred<WorkspaceTreePageDto>();
    let moved = false;
    let movedRootReads = 0;
    operations.tree = vi.fn(async ({ relativePath }): Promise<WorkspaceTreePageDto> => {
      if (relativePath === "e2e-move-target") {
        return {
          entries: moved
            ? [
                {
                  name: "e2e-renamed.txt",
                  relativePath: "e2e-move-target/e2e-renamed.txt",
                  kind: "file",
                  revision: revision("file", "moved"),
                },
              ]
            : [],
          directoryRevision: revision("directory", moved ? "target-moved" : "target-empty"),
        };
      }
      if (!moved) {
        return {
          entries: [
            {
              name: "e2e-renamed.txt",
              relativePath: "e2e-renamed.txt",
              kind: "file",
              revision: revision("file", "source"),
            },
            {
              name: "e2e-move-target",
              relativePath: "e2e-move-target",
              kind: "directory",
              revision: revision("directory", "target-entry"),
              hasChildren: true,
            },
          ],
          directoryRevision: revision("directory", "root-before"),
        };
      }
      movedRootReads += 1;
      if (movedRootReads > 1) return lateRootPage.promise;
      return {
        entries: [
          {
            name: "e2e-move-target",
            relativePath: "e2e-move-target",
            kind: "directory",
            revision: revision("directory", "target-entry-after"),
            hasChildren: true,
          },
        ],
        directoryRevision: revision("directory", "root-after"),
      };
    });
    operations.moveEntry = vi.fn(async () => {
      moved = true;
      return { revision: revision("file", "moved") };
    });

    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("e2e-renamed.txt");
    await waitFor(() => expect(operations.watchStart).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: "展开e2e-move-target" }));
    await waitFor(() =>
      expect(
        vi
          .mocked(operations.tree)
          .mock.calls.some(([input]) => input.relativePath === "e2e-move-target"),
      ).toBe(true),
    );
    const source = screen.getByText("e2e-renamed.txt").closest<HTMLElement>("[data-path]")!;
    const target = screen.getByText("e2e-move-target").closest<HTMLElement>("[data-path]")!;
    const originalElementFromPoint = document.elementFromPoint;
    Object.defineProperty(document, "elementFromPoint", {
      configurable: true,
      value: vi.fn(() => target),
    });
    try {
      await act(async () => {
        dispatchPointer(source, "pointerdown", 41, 2, 2);
        dispatchPointer(document, "pointermove", 41, 20, 20);
        dispatchPointer(document, "pointerup", 41, 20, 20);
      });
      await waitFor(() =>
        expect(operations.moveEntry).toHaveBeenCalledWith(
          expect.objectContaining({
            relativePath: "e2e-renamed.txt",
            targetDirectory: "e2e-move-target",
            expectedRevision: revision("file", "source"),
          }),
        ),
      );
      await screen.findByText("e2e-renamed.txt");

      emit({
        relativePath: "e2e-renamed.txt",
        generation: 5,
        revision: null,
        requiresRescan: false,
      });
      await waitFor(() => expect(movedRootReads).toBe(2));
      await act(async () => {
        lateRootPage.resolve({
          entries: [
            {
              name: "e2e-move-target",
              relativePath: "e2e-move-target",
              kind: "directory",
              revision: revision("directory", "target-entry-late"),
              hasChildren: true,
            },
          ],
          directoryRevision: revision("directory", "root-late"),
        });
        await Promise.resolve();
      });

      expect(screen.getByText("e2e-renamed.txt")).toBeVisible();
      expect(screen.getByRole("button", { name: "折叠e2e-move-target" })).toBeVisible();
    } finally {
      Object.defineProperty(document, "elementFromPoint", {
        configurable: true,
        value: originalElementFromPoint,
      });
    }
  });

  it("rescans and reconciles authoritative tree/read state on focus and visible restoration", async () => {
    const { operations } = createOperations();
    const user = userEvent.setup();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    await user.click(screen.getByText("main.ts"));
    await screen.findByRole("textbox", { name: "编辑文件 main.ts" });

    vi.mocked(operations.readFile).mockResolvedValueOnce({
      path: "main.ts",
      kind: "text",
      content: "external after focus",
      revision: revision("file", "focus-r"),
      encoding: "utf8",
      newline: "lf",
    });
    window.dispatchEvent(new Event("focus"));
    await waitFor(() => expect(operations.watchRescan).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "编辑文件 main.ts" })).toHaveValue(
        "external after focus",
      ),
    );

    const visibilityDescriptor = Object.getOwnPropertyDescriptor(document, "visibilityState");
    try {
      Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
      document.dispatchEvent(new Event("visibilitychange"));
      await act(async () => Promise.resolve());
      expect(operations.watchRescan).toHaveBeenCalledTimes(1);

      vi.mocked(operations.readFile).mockResolvedValueOnce({
        path: "main.ts",
        kind: "text",
        content: "external after visible",
        revision: revision("file", "visible-r"),
        encoding: "utf8",
        newline: "lf",
      });
      Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
      document.dispatchEvent(new Event("visibilitychange"));
      await waitFor(() => expect(operations.watchRescan).toHaveBeenCalledTimes(2));
      await waitFor(() =>
        expect(screen.getByRole("textbox", { name: "编辑文件 main.ts" })).toHaveValue(
          "external after visible",
        ),
      );
    } finally {
      if (visibilityDescriptor === undefined) Reflect.deleteProperty(document, "visibilityState");
      else Object.defineProperty(document, "visibilityState", visibilityDescriptor);
    }
    expect(
      vi.mocked(operations.tree).mock.calls.filter(([input]) => input.relativePath === "").length,
    ).toBeGreaterThanOrEqual(3);
    expect(operations.readFile).toHaveBeenCalledTimes(3);
  });

  it("reconciles from a native focus gain when the DOM focus event is absent", async () => {
    const { operations, emitFocus } = createOperations();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    await waitFor(() => expect(operations.subscribeWindowFocus).toHaveBeenCalledTimes(1));

    vi.mocked(operations.readFile).mockResolvedValueOnce({
      path: "main.ts",
      kind: "text",
      content: "external after native focus",
      revision: revision("file", "native-focus-r"),
      encoding: "utf8",
      newline: "lf",
    });
    emitFocus(false);
    await act(async () => Promise.resolve());
    expect(operations.watchRescan).not.toHaveBeenCalled();
    emitFocus(true);

    await waitFor(() => expect(operations.watchRescan).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "编辑文件 main.ts" })).toHaveValue(
        "external after native focus",
      ),
    );
  });

  it("coalesces overflow rescans and reconciles every open clean or dirty document", async () => {
    const { operations, emit } = createOperations();
    const rescan = deferred<void>();
    operations.watchRescan = vi.fn(() => rescan.promise);
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    fireEvent.click(screen.getByRole("button", { name: "展开src" }));
    await screen.findByText("child.ts");
    fireEvent.click(screen.getByText("child.ts"));
    const childEditor = await screen.findByRole("textbox", { name: "编辑文件 src/child.ts" });
    fireEvent.change(childEditor, { target: { value: "local child draft" } });

    vi.mocked(operations.readFile).mockImplementation(async ({ relativePath }) =>
      relativePath === "main.ts"
        ? {
            path: "main.ts",
            kind: "text",
            content: "external main after overflow",
            revision: revision("file", "overflow-main"),
            encoding: "utf8",
            newline: "lf",
          }
        : {
            path: "src/child.ts",
            kind: "text",
            content: "external child after overflow",
            revision: revision("file", "overflow-child"),
            encoding: "utf8",
            newline: "lf",
          },
    );
    const rootCallsBeforeOverflow = vi
      .mocked(operations.tree)
      .mock.calls.filter(([input]) => input.relativePath === "").length;

    emit({
      relativePath: "",
      generation: 9,
      revision: revision("directory", "overflow-root"),
      requiresRescan: true,
    });
    emit({
      relativePath: "main.ts",
      generation: 9,
      revision: revision("file", "overflow-prefix"),
      requiresRescan: true,
    });
    expect(operations.watchRescan).toHaveBeenCalledTimes(1);
    expect(
      vi.mocked(operations.tree).mock.calls.filter(([input]) => input.relativePath === ""),
    ).toHaveLength(rootCallsBeforeOverflow);

    await act(async () => {
      rescan.resolve(undefined);
      await rescan.promise;
    });
    await waitFor(() => expect(operations.readFile).toHaveBeenCalledTimes(4));
    expect(operations.watchRescan).toHaveBeenCalledTimes(1);
    expect(
      vi.mocked(operations.tree).mock.calls.filter(([input]) => input.relativePath === ""),
    ).toHaveLength(rootCallsBeforeOverflow + 1);
    await waitFor(() => expect(screen.getByText(/文件已在外部修改/)).toBeInTheDocument());
    expect(screen.getByRole("textbox", { name: "编辑文件 src/child.ts" })).toHaveValue(
      "local child draft",
    );

    fireEvent.click(screen.getByRole("tab", { name: "main.ts" }));
    expect(screen.getByRole("textbox", { name: "编辑文件 main.ts" })).toHaveValue(
      "external main after overflow",
    );
  });

  it("debounces from the last key, keeps one in-flight request and saves the later draft after an old ACK", async () => {
    const { operations, resolveSave } = createOperations();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    vi.useFakeTimers();
    fireEvent.change(editor, { target: { value: "draft one" } });
    await vi.advanceTimersByTimeAsync(300);
    fireEvent.change(editor, { target: { value: "draft one latest" } });
    await vi.advanceTimersByTimeAsync(499);
    expect(operations.saveFile).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    await Promise.resolve();
    expect(operations.saveFile).toHaveBeenCalledTimes(1);
    fireEvent.change(editor, { target: { value: "draft two" } });
    await vi.advanceTimersByTimeAsync(500);
    expect(operations.saveFile).toHaveBeenCalledTimes(1);
    await act(async () => {
      resolveSave({ revision: revision("file", "r2"), mutationId: "first" });
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(operations.saveFile).toHaveBeenCalledTimes(2);
    expect(operations.saveFile).toHaveBeenLastCalledWith(
      expect.objectContaining({
        content: "draft two",
        expectedRevision: revision("file", "r2"),
      }),
    );
    await act(async () => {
      resolveSave({ revision: revision("file", "r3"), mutationId: "second" });
      await Promise.resolve();
    });
  });

  it("flushes Ctrl+S and blur immediately while serializing the newest draft behind an in-flight save", async () => {
    const { operations, resolveSave } = createOperations();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    vi.useFakeTimers();

    fireEvent.change(editor, { target: { value: "first immediate" } });
    fireEvent.keyDown(editor, { key: "s", ctrlKey: true });
    expect(operations.saveFile).toHaveBeenCalledTimes(1);
    fireEvent.change(editor, { target: { value: "latest immediate" } });
    fireEvent.blur(editor);
    expect(operations.saveFile).toHaveBeenCalledTimes(1);

    await act(async () => {
      resolveSave({ revision: revision("file", "after-first"), mutationId: "first" });
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(operations.saveFile).toHaveBeenCalledTimes(2);
    expect(operations.saveFile).toHaveBeenLastCalledWith(
      expect.objectContaining({
        content: "latest immediate",
        expectedRevision: revision("file", "after-first"),
      }),
    );
    await act(async () => {
      resolveSave({ revision: revision("file", "after-second"), mutationId: "second" });
      await Promise.resolve();
    });
  });

  it.each(["REVISION_CONFLICT", "CONFLICT"])(
    "maps the %s CAS code into compare, reload, and save-as recovery",
    async (code) => {
      const { operations } = createOperations();
      operations.saveFile = vi.fn(async () => {
        throw { code, message: "native conflict at C:\\private\\main.ts" };
      });
      render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
      await screen.findByText("main.ts");
      fireEvent.click(screen.getByText("main.ts"));
      const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
      fireEvent.change(editor, { target: { value: "local conflict draft" } });
      fireEvent.keyDown(editor, { key: "s", ctrlKey: true });

      await waitFor(() => expect(screen.getByText("外部冲突")).toBeInTheDocument());
      expect(screen.getByRole("button", { name: "比较" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "重新加载" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "另存为" })).toBeInTheDocument();
      expect(screen.queryByText(/private/)).not.toBeInTheDocument();
    },
  );

  it("keeps the conflict recovery message and actions visible in a narrow, short editor", async () => {
    const { operations } = createOperations();
    operations.saveFile = vi.fn(async () => {
      throw { code: "REVISION_CONFLICT", message: "redacted native conflict" };
    });
    render(
      <div style={{ width: 240, height: 112 }}>
        <FilesWorkspace workspaceId="ws_test" operations={operations} />
      </div>,
    );
    fireEvent.click(await screen.findByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    fireEvent.change(editor, { target: { value: "narrow conflict draft" } });
    fireEvent.keyDown(editor, { key: "s", ctrlKey: true });

    const alert = await screen.findByRole("alert");
    const message = within(alert).getByText("文件已在外部修改，请比较或重新加载。");
    const compare = within(alert).getByRole("button", { name: "比较" });
    const reload = within(alert).getByRole("button", { name: "重新加载" });
    const saveAs = within(alert).getByRole("button", { name: "另存为" });
    const activeTab = screen.getByRole("tab", { name: /^main\.ts/ }).parentElement;
    expect(activeTab).not.toBeNull();
    expect(alert).toBeVisible();
    expect(message).toBeVisible();
    expect(compare).toBeVisible();
    expect(reload).toBeVisible();
    expect(saveAs).toBeVisible();
    expect(getComputedStyle(alert).flexShrink).toBe("0");
    expect(getComputedStyle(message).display).toBe("block");
    expect(getComputedStyle(activeTab as HTMLElement).flexShrink).toBe("0");
    expect(getComputedStyle(activeTab as HTMLElement).minWidth).toBe("5.5rem");

    fireEvent.click(saveAs);
    expect(screen.getByRole("dialog", { name: "另存为" })).toBeVisible();
  });

  it("recognizes a watcher echo received before the save ACK as the same mutation", async () => {
    const { operations, emit, resolveSave } = createOperations();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    fireEvent.change(editor, { target: { value: "saved draft" } });
    fireEvent.keyDown(editor, { key: "s", ctrlKey: true });
    await waitFor(() => expect(operations.saveFile).toHaveBeenCalledTimes(1));
    const saveInput = vi.mocked(operations.saveFile).mock.calls[0]?.[0];
    expect(saveInput).toBeDefined();
    const savedRevision = revision("file", "own-save", 11);

    emit({
      relativePath: "main.ts",
      generation: 2,
      revision: savedRevision,
      requiresRescan: false,
    });
    resolveSave({ revision: savedRevision, mutationId: saveInput?.mutationId ?? "save" });

    await waitFor(() => expect(screen.getByText("已保存")).toBeInTheDocument());
    expect(screen.queryByText(/外部冲突/)).not.toBeInTheDocument();
    expect(operations.readFile).toHaveBeenCalledTimes(1);
  });

  it("auto-reloads an open clean document from an authoritative watcher read", async () => {
    const { operations, emit } = createOperations();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    const externalRevision = revision("file", "external-clean", 24);
    vi.mocked(operations.readFile).mockResolvedValueOnce({
      path: "main.ts",
      kind: "text",
      content: "external clean content",
      revision: externalRevision,
      encoding: "utf8",
      newline: "lf",
    });

    emit({
      relativePath: "main.ts",
      generation: 5,
      revision: externalRevision,
      requiresRescan: false,
    });

    await waitFor(() => expect(editor).toHaveValue("external clean content"));
    expect(screen.getByText("已保存")).toBeInTheDocument();
    expect(screen.queryByText(/外部冲突/)).not.toBeInTheDocument();
  });

  /**
   * 复现 Watcher hint 重复缓存 CAS revision、但随后权威读取观察到新字节的 native
   * 竞态，确保重复 hint 不能跳过真实读取。
   */
  it("authoritatively reloads a clean document when the watcher hint repeats the cached revision", async () => {
    const { operations, emit } = createOperations();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    vi.mocked(operations.readFile).mockResolvedValueOnce({
      path: "main.ts",
      kind: "text",
      content: "authoritative content after repeated hint",
      revision: revision("file", "authoritative-after-repeat", 41),
      encoding: "utf8",
      newline: "lf",
    });

    emit({
      relativePath: "main.ts",
      generation: 5,
      revision: revision("file", "main-r"),
      requiresRescan: false,
    });

    await waitFor(() => expect(editor).toHaveValue("authoritative content after repeated hint"));
    expect(operations.readFile).toHaveBeenCalledTimes(2);
    expect(screen.getByText("已保存")).toBeInTheDocument();
    expect(screen.queryByText(/外部冲突/)).not.toBeInTheDocument();
  });

  /**
   * dirty 状态收到重复 hint 时仍必须执行权威读取；磁盘 revision 未变化时保留草稿
   * 并恢复自动保存，不能把自身回声误判为冲突。
   */
  it("authoritatively checks a dirty document when the watcher hint repeats the cached revision", async () => {
    const { operations, emit } = createOperations();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    fireEvent.change(editor, { target: { value: "local draft after repeated hint" } });
    vi.mocked(operations.readFile).mockResolvedValueOnce({
      path: "main.ts",
      kind: "text",
      content: "export const main = true;",
      revision: revision("file", "r1"),
      encoding: "utf8",
      newline: "lf",
    });

    emit({
      relativePath: "main.ts",
      generation: 5,
      revision: revision("file", "r1"),
      requiresRescan: false,
    });

    await waitFor(() => expect(operations.readFile).toHaveBeenCalledTimes(2));
    expect(editor).toHaveValue("local draft after repeated hint");
    expect(screen.queryByText(/外部冲突/)).not.toBeInTheDocument();
  });

  /**
   * 复现父树在首次文件读取前发布新 revision 的重复 native hint；编辑器新鲜度必须
   * 继续绑定 document.revision，第二次读取必须胜过过期请求。
   */
  it("projects the latest clean read when the tree revision races ahead of editor content", async () => {
    const { operations, emit } = createOperations();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    const externalRevision = revision("file", "tree-ahead", 38);
    const firstExternalRead = deferred<FileReadDto>();
    const watcherTree = deferred<WorkspaceTreePageDto>();
    vi.mocked(operations.readFile)
      .mockImplementationOnce(() => firstExternalRead.promise)
      .mockResolvedValueOnce({
        path: "main.ts",
        kind: "text",
        content: "latest content after tree race",
        revision: externalRevision,
        encoding: "utf8",
        newline: "lf",
      });
    vi.mocked(operations.tree).mockImplementationOnce(() => watcherTree.promise);

    emit({
      relativePath: "main.ts",
      generation: 5,
      revision: externalRevision,
      requiresRescan: false,
    });
    await waitFor(() => expect(operations.readFile).toHaveBeenCalledTimes(2));
    await act(async () => {
      watcherTree.resolve({
        entries: [
          { name: "main.ts", relativePath: "main.ts", kind: "file", revision: externalRevision },
        ],
        directoryRevision: revision("directory", "root-after-tree-race"),
      });
      await watcherTree.promise;
      await Promise.resolve();
    });

    emit({
      relativePath: "main.ts",
      generation: 5,
      revision: externalRevision,
      requiresRescan: false,
    });

    await waitFor(() => expect(operations.readFile).toHaveBeenCalledTimes(3));
    await waitFor(() => expect(editor).toHaveValue("latest content after tree race"));
    await act(async () => {
      firstExternalRead.resolve({
        path: "main.ts",
        kind: "text",
        content: "stale first read",
        revision: externalRevision,
        encoding: "utf8",
        newline: "lf",
      });
      await firstExternalRead.promise;
    });
    expect(editor).toHaveValue("latest content after tree race");
  });

  it("keeps clean watcher reload live after conflict save-as and explicit reload", async () => {
    const { operations, emit } = createOperations();
    const user = userEvent.setup();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await user.click(await screen.findByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    fireEvent.change(editor, { target: { value: "local conflict draft" } });
    const conflictRevision = revision("file", "external-conflict", 25);
    const conflictRead: FileReadDto = {
      path: "main.ts",
      kind: "text",
      content: "external conflict content",
      revision: conflictRevision,
      encoding: "utf8",
      newline: "lf",
    };
    vi.mocked(operations.readFile).mockResolvedValueOnce(conflictRead);
    emit({
      relativePath: "main.ts",
      generation: 6,
      revision: conflictRevision,
      requiresRescan: false,
    });
    await screen.findByText(/文件已在外部修改/);

    vi.mocked(operations.saveFile).mockResolvedValueOnce({
      revision: revision("file", "copy-saved"),
      mutationId: "copy-save",
    });
    await user.click(screen.getByRole("button", { name: "另存为" }));
    const saveAs = screen.getByRole("dialog", { name: "另存为" });
    fireEvent.change(within(saveAs).getByRole("textbox", { name: "工作区相对路径" }), {
      target: { value: "main.copy.ts" },
    });
    await user.click(within(saveAs).getByRole("button", { name: "保存副本" }));
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "另存为" })).not.toBeInTheDocument(),
    );

    await user.click(screen.getByRole("tab", { name: /main\.ts/ }));
    vi.mocked(operations.readFile).mockResolvedValueOnce(conflictRead);
    await user.click(screen.getByRole("button", { name: "重新加载" }));
    await waitFor(() => expect(editor).toHaveValue("external conflict content"));
    const watcherRevision = revision("file", "external-clean-after-conflict", 26);
    vi.mocked(operations.readFile).mockResolvedValueOnce({
      ...conflictRead,
      content: "watcher after conflict",
      revision: watcherRevision,
    });

    emit({
      relativePath: "main.ts",
      generation: 6,
      revision: watcherRevision,
      requiresRescan: false,
    });

    await waitFor(() => expect(editor).toHaveValue("watcher after conflict"));
    expect(screen.getByText("已保存")).toBeInTheDocument();
    expect(screen.queryByText(/外部冲突/)).not.toBeInTheDocument();
  });

  it("turns a non-matching watcher event before save ACK into an external conflict", async () => {
    const { operations, emit, resolveSave } = createOperations();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    const externalRevision = revision("file", "external-after-save", 19);
    vi.mocked(operations.readFile).mockResolvedValueOnce({
      path: "main.ts",
      kind: "text",
      content: "external content",
      revision: externalRevision,
      encoding: "utf8",
      newline: "lf",
    });
    fireEvent.change(editor, { target: { value: "local during save" } });
    fireEvent.keyDown(editor, { key: "s", ctrlKey: true });
    await waitFor(() => expect(operations.saveFile).toHaveBeenCalledTimes(1));
    const saveInput = vi.mocked(operations.saveFile).mock.calls[0]?.[0];
    const ownRevision = revision("file", "own-save", 17);

    emit({
      relativePath: "main.ts",
      generation: 3,
      revision: externalRevision,
      requiresRescan: false,
    });
    resolveSave({ revision: ownRevision, mutationId: saveInput?.mutationId ?? "save" });

    await waitFor(() => expect(screen.getByText(/文件已在外部修改/)).toBeInTheDocument());
    expect(screen.getByRole("textbox", { name: "编辑文件 main.ts" })).toHaveValue(
      "local during save",
    );
    fireEvent.click(screen.getByRole("button", { name: "比较" }));
    // CodeMirror 的 Diff chunk 按需加载；全量并行测试下允许有限加载时间，但仍要求真实只读视图出现。
    expect(
      await screen.findByLabelText("只读 Diff main.ts", undefined, { timeout: 5_000 }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("文件冲突比较")).toHaveTextContent("外部版本本地草稿");
    expect(operations.saveFile).toHaveBeenCalledTimes(1);
  });

  it("does not overwrite a draft created while a clean-file watcher read is pending", async () => {
    const { operations, emit } = createOperations();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    const externalRead = deferred<FileReadDto>();
    vi.mocked(operations.readFile).mockImplementationOnce(() => externalRead.promise);
    const externalRevision = revision("file", "external-pending", 21);

    emit({
      relativePath: "main.ts",
      generation: 4,
      revision: externalRevision,
      requiresRescan: false,
    });
    await waitFor(() => expect(operations.readFile).toHaveBeenCalledTimes(2));
    fireEvent.change(editor, { target: { value: "draft while checking" } });
    externalRead.resolve({
      path: "main.ts",
      kind: "text",
      content: "external pending content",
      revision: externalRevision,
      encoding: "utf8",
      newline: "lf",
    });

    await waitFor(() => expect(screen.getByText(/文件已在外部修改/)).toBeInTheDocument());
    expect(screen.getByRole("textbox", { name: "编辑文件 main.ts" })).toHaveValue(
      "draft while checking",
    );
    expect(operations.saveFile).not.toHaveBeenCalled();
  });

  it("surfaces a watcher conflict and offers reload/compare without force overwrite", async () => {
    const { operations, emit } = createOperations();
    const user = userEvent.setup();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    await user.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    fireEvent.change(editor, { target: { value: "local draft" } });
    vi.mocked(operations.readFile).mockResolvedValueOnce({
      path: "main.ts",
      kind: "text",
      content: "external draft",
      revision: revision("file", "external-r"),
      encoding: "utf8",
      newline: "lf",
    });
    emit({
      relativePath: "main.ts",
      generation: 1,
      revision: revision("file", "external-r"),
      requiresRescan: false,
    });
    await waitFor(() => expect(screen.getByText(/外部修改/)).toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "比较" }));
    expect(await screen.findByLabelText("只读 Diff main.ts")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "返回编辑" }));
    expect(operations.saveFile).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "重新加载" }));
    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "编辑文件 main.ts" })).toHaveValue(
        "export const main = true;",
      ),
    );
  });

  it("rejects absolute save-as input and atomically creates the relative copy with its content", async () => {
    const { operations, emit } = createOperations();
    const user = userEvent.setup();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    await user.click(screen.getByText("main.ts"));
    fireEvent.change(await screen.findByRole("textbox", { name: "编辑文件 main.ts" }), {
      target: { value: "local draft" },
    });
    vi.mocked(operations.readFile).mockResolvedValueOnce({
      path: "main.ts",
      kind: "text",
      content: "external draft",
      revision: revision("file", "external-save-as"),
      encoding: "utf8",
      newline: "lf",
    });
    emit({
      relativePath: "main.ts",
      generation: 1,
      revision: revision("file", "external-save-as"),
      requiresRescan: false,
    });
    await screen.findByText(/文件已在外部修改/);

    await user.click(screen.getByRole("button", { name: "另存为" }));
    const pathInput = screen.getByRole("textbox", { name: "工作区相对路径" });
    fireEvent.change(pathInput, { target: { value: "C:\\private\\main.ts" } });
    await user.click(screen.getByRole("button", { name: "保存副本" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("路径必须位于当前工作区内");
    expect(operations.createEntry).not.toHaveBeenCalled();

    fireEvent.change(pathInput, { target: { value: "main.copy.ts" } });
    await user.click(screen.getByRole("button", { name: "保存副本" }));
    await waitFor(() =>
      expect(operations.createEntry).toHaveBeenCalledWith({
        workspaceId: "ws_test",
        relativePath: "main.copy.ts",
        expectedRevision: null,
        mutationId: expect.any(String),
        kind: "file",
        initialContent: {
          content: "local draft",
          encoding: "utf8",
          newline: "lf",
        },
      }),
    );
    expect(operations.saveFile).not.toHaveBeenCalled();
    expect(await screen.findByRole("tab", { name: "main.copy.ts" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("textbox", { name: "编辑文件 main.copy.ts" })).toHaveValue(
      "local draft",
    );
  });

  it("latches native recovery, cancels future writes, and keeps authoritative reads available", async () => {
    const { operations, emitDrop } = createOperations();
    operations.createEntry = vi.fn(async () => {
      throw { code: "WORKSPACE_RECOVERY_REQUIRED" };
    });
    const onNotice = vi.fn();
    const user = userEvent.setup();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} onNotice={onNotice} />);
    await screen.findByText("main.ts");
    await waitFor(() => expect(operations.subscribeNativeDrop).toHaveBeenCalledOnce());

    await user.click(screen.getByRole("button", { name: "新建文件" }));
    await user.type(screen.getByRole("textbox", { name: "新建文件名" }), "unsafe.ts");
    await user.keyboard("{Enter}");

    expect(await screen.findByText("工作区写入已暂停")).toBeVisible();
    expect(onNotice).toHaveBeenCalledWith(
      "工作区写入已停止。请核对相关文件后重新打开工作区或重启 Ja。",
    );
    expect(screen.queryByRole("button", { name: "新建文件" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "新建目录" })).not.toBeInTheDocument();

    const readsBeforeRefresh = vi.mocked(operations.tree).mock.calls.length;
    await user.click(screen.getByRole("button", { name: "刷新文件树" }));
    await waitFor(() =>
      expect(vi.mocked(operations.tree).mock.calls.length).toBeGreaterThan(readsBeforeRefresh),
    );

    const tree = screen.getByTestId("file-tree");
    const originalElementFromPoint = document.elementFromPoint;
    Object.defineProperty(document, "elementFromPoint", {
      configurable: true,
      value: vi.fn(() => tree),
    });
    try {
      emitDrop({ dropToken: "blocked-after-recovery", x: 10, y: 10 });
      await act(async () => Promise.resolve());
    } finally {
      Object.defineProperty(document, "elementFromPoint", {
        configurable: true,
        value: originalElementFromPoint,
      });
    }
    expect(operations.importDrop).not.toHaveBeenCalled();
  });

  it("routes create, rename and trash controls only through injected operations", async () => {
    const { operations } = createOperations();
    const user = userEvent.setup();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    await user.click(screen.getByRole("button", { name: "新建文件" }));
    await user.type(screen.getByRole("textbox", { name: "新建文件名" }), "new.ts");
    await user.keyboard("{Enter}");
    await waitFor(() =>
      expect(operations.createEntry).toHaveBeenCalledWith(
        expect.objectContaining({ relativePath: "new.ts", kind: "file", expectedRevision: null }),
      ),
    );
    fireEvent.contextMenu(within(screen.getByTestId("file-tree")).getByText("main.ts"));
    await user.click(screen.getByRole("menuitem", { name: "重命名" }));
    const rename = screen.getByRole("textbox", { name: "重命名 main.ts" });
    await user.clear(rename);
    await user.type(rename, "index.ts");
    await user.keyboard("{Enter}");
    await waitFor(() =>
      expect(operations.moveEntry).toHaveBeenCalledWith(
        expect.objectContaining({
          relativePath: "main.ts",
          targetDirectory: "",
          newName: "index.ts",
          expectedRevision: revision("file", "r1"),
        }),
      ),
    );
    fireEvent.contextMenu(
      within(screen.getByRole("treeitem", { name: "main.ts" })).getByText("main.ts"),
    );
    await user.click(screen.getByRole("menuitem", { name: "移入回收站" }));
    await waitFor(() =>
      expect(operations.trashPrepare).toHaveBeenCalledWith(
        expect.objectContaining({
          relativePath: "main.ts",
          expectedRevision: revision("file", "r1"),
        }),
      ),
    );
    const trashDialog = await screen.findByRole("alertdialog", { name: "移入回收站" });
    expect(within(trashDialog).getByText("1 个文件")).toBeVisible();
    expect(within(trashDialog).getByText("10 字节")).toBeVisible();
    expect(trashDialog).not.toHaveTextContent("trash-1");
    expect(operations.trashCommit).not.toHaveBeenCalled();
    await user.click(within(trashDialog).getByRole("button", { name: "移入回收站" }));
    await waitFor(() =>
      expect(operations.trashCommit).toHaveBeenCalledWith(
        expect.objectContaining({ operationToken: "trash-1" }),
      ),
    );
    const prepareMutationId = vi.mocked(operations.trashPrepare!).mock.calls[0]?.[0].mutationId;
    const commitMutationId = vi.mocked(operations.trashCommit!).mock.calls[0]?.[0].mutationId;
    expect(prepareMutationId).toEqual(expect.any(String));
    expect(commitMutationId).toEqual(expect.any(String));
    expect(commitMutationId).not.toBe(prepareMutationId);
  });

  it("cancels a prepared trash request without committing or removing the node", async () => {
    const { operations } = createOperations();
    const user = userEvent.setup();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");

    fireEvent.contextMenu(
      within(screen.getByRole("treeitem", { name: "main.ts" })).getByText("main.ts"),
    );
    await user.click(screen.getByRole("menuitem", { name: "移入回收站" }));
    const trashDialog = await screen.findByRole("alertdialog", { name: "移入回收站" });
    await within(trashDialog).findByText("10 字节");
    await user.click(within(trashDialog).getByRole("button", { name: "取消" }));

    await waitFor(() =>
      expect(screen.queryByRole("alertdialog", { name: "移入回收站" })).not.toBeInTheDocument(),
    );
    expect(operations.trashCommit).not.toHaveBeenCalled();
    expect(screen.getByTestId("file-tree").querySelector('[data-path="main.ts"]')).not.toBeNull();
  });

  it("blocks trash preparation while the target or a directory descendant has an unsaved draft", async () => {
    const { operations } = createOperations();
    const onNotice = vi.fn();
    const user = userEvent.setup();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} onNotice={onNotice} />);
    await screen.findByText("main.ts");

    await user.click(screen.getByRole("button", { name: "展开src" }));
    await screen.findByText("child.ts");
    await user.click(
      within(screen.getByRole("treeitem", { name: "child.ts" })).getByText("child.ts"),
    );
    fireEvent.change(await screen.findByRole("textbox", { name: "编辑文件 src/child.ts" }), {
      target: { value: "export const child = false;" },
    });

    fireEvent.contextMenu(within(screen.getByRole("treeitem", { name: "src" })).getByText("src"));
    await user.click(screen.getByRole("menuitem", { name: "移入回收站" }));

    expect(operations.trashPrepare).not.toHaveBeenCalled();
    expect(operations.trashCommit).not.toHaveBeenCalled();
    expect(screen.queryByRole("alertdialog", { name: "移入回收站" })).not.toBeInTheDocument();
    expect(onNotice).toHaveBeenCalledWith(
      "存在未保存、保存中或冲突的文件，请先完成保存或处理冲突后再移入回收站。",
    );
    expect(screen.getByRole("textbox", { name: "编辑文件 src/child.ts" })).toHaveValue(
      "export const child = false;",
    );
  });

  it("keeps prepare and commit failures visible without deleting the file projection", async () => {
    const { operations } = createOperations();
    const user = userEvent.setup();
    vi.mocked(operations.trashPrepare!).mockRejectedValueOnce(new Error("native prepare details"));
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");

    fireEvent.contextMenu(
      within(screen.getByRole("treeitem", { name: "main.ts" })).getByText("main.ts"),
    );
    await user.click(screen.getByRole("menuitem", { name: "移入回收站" }));
    let trashDialog = await screen.findByRole("alertdialog", { name: "移入回收站" });
    expect(await within(trashDialog).findByRole("alert")).toHaveTextContent("文件没有被删除");
    expect(trashDialog).not.toHaveTextContent("native prepare details");
    expect(operations.trashCommit).not.toHaveBeenCalled();
    expect(screen.getByTestId("file-tree").querySelector('[data-path="main.ts"]')).not.toBeNull();
    await user.click(within(trashDialog).getByRole("button", { name: "取消" }));

    vi.mocked(operations.trashPrepare!).mockResolvedValueOnce({
      operationToken: "trash-commit-failure",
      fileCount: 1,
      totalBytes: 10,
      expiresAtUnixMillis: Date.now() + 30_000,
    });
    vi.mocked(operations.trashCommit!).mockRejectedValueOnce(new Error("native commit details"));
    fireEvent.contextMenu(
      within(screen.getByRole("treeitem", { name: "main.ts" })).getByText("main.ts"),
    );
    await user.click(screen.getByRole("menuitem", { name: "移入回收站" }));
    trashDialog = await screen.findByRole("alertdialog", { name: "移入回收站" });
    await within(trashDialog).findByText("10 字节");
    await user.click(within(trashDialog).getByRole("button", { name: "移入回收站" }));

    expect(await within(trashDialog).findByRole("alert")).toHaveTextContent("目标仍在工作区");
    expect(trashDialog).not.toHaveTextContent("native commit details");
    expect(within(trashDialog).getByRole("button", { name: "移入回收站" })).toBeDisabled();
    expect(screen.getByTestId("file-tree").querySelector('[data-path="main.ts"]')).not.toBeNull();
    expect(vi.mocked(operations.tree).mock.calls.at(-1)?.[0]).toEqual(
      expect.objectContaining({ relativePath: "" }),
    );
  });

  it("explains a disabled system recycle bin and preserves the file projection", async () => {
    const { operations } = createOperations();
    const onNotice = vi.fn();
    const user = userEvent.setup();
    vi.mocked(operations.trashCommit!).mockRejectedValueOnce({ code: "RECYCLE_UNAVAILABLE" });
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} onNotice={onNotice} />);
    await screen.findByText("main.ts");

    fireEvent.contextMenu(
      within(screen.getByRole("treeitem", { name: "main.ts" })).getByText("main.ts"),
    );
    await user.click(screen.getByRole("menuitem", { name: "移入回收站" }));
    const trashDialog = await screen.findByRole("alertdialog", { name: "移入回收站" });
    await within(trashDialog).findByText("10 字节");
    await user.click(within(trashDialog).getByRole("button", { name: "移入回收站" }));

    expect(await within(trashDialog).findByRole("alert")).toHaveTextContent(
      "当前磁盘未启用系统回收站，文件没有被删除",
    );
    expect(onNotice).toHaveBeenCalledWith("当前磁盘未启用系统回收站，文件没有被删除。");
    expect(screen.getByTestId("file-tree").querySelector('[data-path="main.ts"]')).not.toBeNull();
    expect(vi.mocked(operations.tree).mock.calls.at(-1)?.[0]).toEqual(
      expect.objectContaining({ relativePath: "" }),
    );
  });

  it("reconciles an uncertain commit as completed when the authoritative parent no longer contains the target", async () => {
    const { operations } = createOperations();
    const onNotice = vi.fn();
    const user = userEvent.setup();
    vi.mocked(operations.trashCommit!).mockRejectedValueOnce(new Error("lost response"));
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} onNotice={onNotice} />);
    await screen.findByText("main.ts");

    vi.mocked(operations.tree).mockResolvedValueOnce({
      entries: [
        {
          name: "src",
          relativePath: "src",
          kind: "directory",
          revision: revision("directory", "d1"),
          hasChildren: true,
        },
      ],
      directoryRevision: revision("directory", "root-after-trash"),
    });
    fireEvent.contextMenu(
      within(screen.getByRole("treeitem", { name: "main.ts" })).getByText("main.ts"),
    );
    await user.click(screen.getByRole("menuitem", { name: "移入回收站" }));
    const trashDialog = await screen.findByRole("alertdialog", { name: "移入回收站" });
    await within(trashDialog).findByText("10 字节");
    await user.click(within(trashDialog).getByRole("button", { name: "移入回收站" }));

    await waitFor(() =>
      expect(screen.queryByRole("alertdialog", { name: "移入回收站" })).not.toBeInTheDocument(),
    );
    expect(screen.getByTestId("file-tree").querySelector('[data-path="main.ts"]')).toBeNull();
    expect(onNotice).toHaveBeenCalledWith(
      "已重新读取文件树，目标不再存在；系统回收站操作可能已经完成。",
    );
  });

  it("expires and clears prepared trash UI across time and workspace changes", async () => {
    const { operations } = createOperations();
    const user = userEvent.setup();
    vi.mocked(operations.trashPrepare!).mockResolvedValueOnce({
      operationToken: "expired-secret",
      fileCount: 1,
      totalBytes: 10,
      expiresAtUnixMillis: Date.now() - 1,
    });
    const view = render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");

    fireEvent.contextMenu(
      within(screen.getByRole("treeitem", { name: "main.ts" })).getByText("main.ts"),
    );
    await user.click(screen.getByRole("menuitem", { name: "移入回收站" }));
    let trashDialog = await screen.findByRole("alertdialog", { name: "移入回收站" });
    await within(trashDialog).findByText("10 字节");
    await user.click(within(trashDialog).getByRole("button", { name: "移入回收站" }));
    expect(await within(trashDialog).findByRole("alert")).toHaveTextContent("确认已过期");
    expect(operations.trashCommit).not.toHaveBeenCalled();
    expect(document.body).not.toHaveTextContent("expired-secret");
    await user.click(within(trashDialog).getByRole("button", { name: "取消" }));

    const latePrepare = deferred<TrashPrepareResult>();
    vi.mocked(operations.trashPrepare!).mockImplementationOnce(() => latePrepare.promise);
    fireEvent.contextMenu(
      within(screen.getByRole("treeitem", { name: "main.ts" })).getByText("main.ts"),
    );
    await user.click(screen.getByRole("menuitem", { name: "移入回收站" }));
    trashDialog = await screen.findByRole("alertdialog", { name: "移入回收站" });
    expect(within(trashDialog).getByRole("status")).toHaveTextContent("正在核对");

    view.rerender(<FilesWorkspace workspaceId="ws_next" operations={operations} />);
    await waitFor(() =>
      expect(screen.queryByRole("alertdialog", { name: "移入回收站" })).not.toBeInTheDocument(),
    );
    await act(async () => {
      latePrepare.resolve({
        operationToken: "late-secret",
        fileCount: 1,
        totalBytes: 10,
        expiresAtUnixMillis: Date.now() + 30_000,
      });
      await latePrepare.promise;
    });
    expect(screen.queryByRole("alertdialog", { name: "移入回收站" })).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("late-secret");
    expect(operations.trashCommit).not.toHaveBeenCalled();
  });

  it("uses the authoritative root directory revision for an opaque drop token", async () => {
    const { operations, emitDrop } = createOperations();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    await waitFor(() => expect(operations.subscribeNativeDrop).toHaveBeenCalledTimes(1));
    const tree = screen.getByTestId("file-tree");
    const originalElementFromPoint = document.elementFromPoint;
    Object.defineProperty(document, "elementFromPoint", {
      configurable: true,
      value: vi.fn(() => tree),
    });
    try {
      emitDrop({ dropToken: "drop-root", x: 10, y: 10 });
      await waitFor(() =>
        expect(operations.importDrop).toHaveBeenCalledWith(
          expect.objectContaining({
            workspaceId: "ws_test",
            targetDirectory: "",
            dropToken: "drop-root",
            expectedRevision: revision("directory", "root"),
          }),
        ),
      );
    } finally {
      Object.defineProperty(document, "elementFromPoint", {
        configurable: true,
        value: originalElementFromPoint,
      });
    }
  });

  it("remaps the open document path, revision and pending autosave after rename", async () => {
    const { operations } = createOperations();
    const user = userEvent.setup();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    fireEvent.change(editor, { target: { value: "dirty before rename" } });
    fireEvent.contextMenu(within(screen.getByTestId("file-tree")).getByText("main.ts"));
    await user.click(screen.getByRole("menuitem", { name: "重命名" }));
    const rename = screen.getByRole("textbox", { name: "重命名 main.ts" });
    await user.clear(rename);
    await user.type(rename, "index.ts");
    await user.keyboard("{Enter}");

    const movedEditor = await screen.findByRole("textbox", { name: "编辑文件 index.ts" });
    fireEvent.keyDown(movedEditor, { key: "s", ctrlKey: true });
    await waitFor(() =>
      expect(operations.saveFile).toHaveBeenCalledWith(
        expect.objectContaining({
          relativePath: "index.ts",
          expectedRevision: revision("file", "move-r"),
          content: "dirty before rename",
        }),
      ),
    );
  });

  it("waits for the latest save before granting a workspace-change lease", async () => {
    const { operations, resolveSave } = createOperations();
    let lifecycle: FilesWorkspaceLifecycle | undefined;
    render(
      <FilesWorkspace
        workspaceId="ws_test"
        operations={operations}
        onRegisterLifecycle={(next) => {
          lifecycle = next;
        }}
      />,
    );
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    fireEvent.change(editor, { target: { value: "switch-safe draft" } });
    expect(lifecycle?.workspaceId).toBe("ws_test");

    let settled = false;
    const barrier = lifecycle!.flushForWorkspaceChange().then((lease) => {
      settled = true;
      return lease;
    });
    expect(operations.saveFile).toHaveBeenCalledTimes(1);
    expect(settled).toBe(false);
    await waitFor(() => expect(editor).toHaveAttribute("readonly"));

    let lease!: Awaited<typeof barrier>;
    await act(async () => {
      resolveSave({ revision: revision("file", "switch-safe"), mutationId: "switch-save" });
      lease = await barrier;
    });
    expect(settled).toBe(true);
    expect(screen.getByText("已保存")).toBeInTheDocument();
    expect(editor).toHaveAttribute("readonly");
    lease.release();
    await waitFor(() => expect(editor).not.toHaveAttribute("readonly"));
  });

  it("rejects workspace change on save failure while retaining and re-enabling the old draft", async () => {
    const { operations } = createOperations();
    operations.saveFile = vi.fn(async () => {
      throw new Error("native path must stay hidden");
    });
    const onNotice = vi.fn();
    let lifecycle: FilesWorkspaceLifecycle | undefined;
    render(
      <FilesWorkspace
        workspaceId="ws_test"
        operations={operations}
        onNotice={onNotice}
        onRegisterLifecycle={(next) => {
          lifecycle = next;
        }}
      />,
    );
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    fireEvent.change(editor, { target: { value: "draft retained after failure" } });

    await act(async () => {
      await expect(lifecycle!.flushForWorkspaceChange()).rejects.toThrow("文件草稿未能安全保存");
    });

    expect(screen.getByRole("textbox", { name: "编辑文件 main.ts" })).toHaveValue(
      "draft retained after failure",
    );
    expect(screen.getByText("保存失败")).toBeInTheDocument();
    expect(onNotice).toHaveBeenCalledWith("当前工作区仍有未保存或冲突的文件，请处理后再切换。");
    expect(editor).not.toHaveAttribute("readonly");
    fireEvent.change(editor, { target: { value: "editing recovered" } });
    expect(editor).toHaveValue("editing recovered");
  });

  /**
   * 隐藏 Files 必须保留 controller/lifecycle，但不能预读目录或注册原生观察者；重新显示时
   * 通过权威对账恢复，随后再次隐藏要完整释放本轮订阅。
   */
  it("defers native Files activity until visible and suspends it again when hidden", async () => {
    const { operations } = createOperations();
    const stop = vi.fn(async () => undefined);
    const firstWatchSubscription = deferred<WatchSubscription>();
    const unlistenFocus = vi.fn(async () => undefined);
    const unlistenDrop = vi.fn(async () => undefined);
    operations.watchStart = vi
      .fn()
      .mockImplementationOnce(() => firstWatchSubscription.promise)
      .mockResolvedValue({ stop });
    operations.subscribeWindowFocus = vi.fn(async () => unlistenFocus);
    operations.subscribeNativeDrop = vi.fn(async () => unlistenDrop);

    const view = render(
      <FilesWorkspace workspaceId="ws_test" operations={operations} activityEnabled={false} />,
    );
    await act(async () => Promise.resolve());

    expect(operations.tree).not.toHaveBeenCalled();
    expect(operations.watchStart).not.toHaveBeenCalled();
    expect(operations.subscribeWindowFocus).not.toHaveBeenCalled();
    expect(operations.subscribeNativeDrop).not.toHaveBeenCalled();

    view.rerender(
      <FilesWorkspace workspaceId="ws_test" operations={operations} activityEnabled={true} />,
    );
    await screen.findByText("main.ts");
    await waitFor(() => expect(operations.watchStart).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(operations.subscribeWindowFocus).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(operations.subscribeNativeDrop).toHaveBeenCalledTimes(1));
    expect(operations.watchRescan).not.toHaveBeenCalled();
    await act(async () => {
      firstWatchSubscription.resolve({ stop });
      await Promise.resolve();
    });
    const rootReads = vi
      .mocked(operations.tree)
      .mock.calls.filter(([input]) => input.relativePath === "").length;

    view.rerender(
      <FilesWorkspace workspaceId="ws_test" operations={operations} activityEnabled={false} />,
    );
    await waitFor(() => expect(stop).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(unlistenFocus).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(unlistenDrop).toHaveBeenCalledTimes(1));

    view.rerender(
      <FilesWorkspace workspaceId="ws_test" operations={operations} activityEnabled={true} />,
    );
    await waitFor(() => expect(operations.watchStart).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(
        vi.mocked(operations.tree).mock.calls.filter(([input]) => input.relativePath === ""),
      ).toHaveLength(rootReads + 1),
    );
    expect(operations.watchRescan).not.toHaveBeenCalled();
  });

  it("cleans up watcher and native-drop listeners when async subscriptions resolve after unmount", async () => {
    const { operations } = createOperations();
    const watchSubscription = deferred<WatchSubscription>();
    const nativeSubscription = deferred<() => void | Promise<void>>();
    const stop = vi.fn(async () => undefined);
    const unlisten = vi.fn(async () => undefined);
    operations.watchStart = vi.fn(() => watchSubscription.promise);
    operations.subscribeNativeDrop = vi.fn(() => nativeSubscription.promise);

    const view = render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await waitFor(() => expect(operations.watchStart).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(operations.subscribeNativeDrop).toHaveBeenCalledTimes(1));
    view.unmount();
    await act(async () => {
      watchSubscription.resolve({ stop });
      nativeSubscription.resolve(unlisten);
      await Promise.resolve();
    });

    await waitFor(() => expect(stop).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(unlisten).toHaveBeenCalledTimes(1));
    expect(operations.watchStop).not.toHaveBeenCalled();
  });

  /**
   * Workspace 重绑会让旧 watcher 的 stop ACK 以稳定错误结束；effect cleanup 必须消费该拒绝，
   * 不能把已由下一次 native start/退出清理接管的资源回收升级为 renderer pageerror。
   */
  it("contains watcher stop rejection during workspace replacement", async () => {
    const { operations } = createOperations();
    const stop = vi.fn(async () => {
      throw new Error("workspace already replaced");
    });
    operations.watchStart = vi.fn(async () => ({ stop }));

    const view = render(<FilesWorkspace workspaceId="ws_old" operations={operations} />);
    await waitFor(() => expect(operations.watchStart).toHaveBeenCalledTimes(1));

    view.rerender(<FilesWorkspace workspaceId="ws_next" operations={operations} />);

    await waitFor(() => expect(stop).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(operations.watchStart).toHaveBeenCalledTimes(2));
    view.unmount();
    await waitFor(() => expect(stop).toHaveBeenCalledTimes(2));
  });

  it("uses an application dialog whose dirty-draft cancel performs no save or close", async () => {
    const { operations } = createOperations();
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    vi.useFakeTimers();
    fireEvent.change(editor, { target: { value: "dirty" } });
    fireEvent.click(screen.getByRole("button", { name: "关闭 main.ts" }));
    const dialog = screen.getByRole("alertdialog", { name: "关闭未保存文件" });
    fireEvent.click(within(dialog).getByRole("button", { name: "取消" }));
    expect(screen.getByRole("tab", { name: /main\.ts/ })).toBeInTheDocument();
    expect(operations.saveFile).not.toHaveBeenCalled();
    expect(screen.queryByRole("alertdialog", { name: "关闭未保存文件" })).not.toBeInTheDocument();
  });

  it("cancels conflict-draft close without another native write", async () => {
    const { operations } = createOperations();
    operations.saveFile = vi.fn(async () => {
      throw { code: "REVISION_CONFLICT" };
    });
    render(<FilesWorkspace workspaceId="ws_test" operations={operations} />);
    await screen.findByText("main.ts");
    fireEvent.click(screen.getByText("main.ts"));
    const editor = await screen.findByRole("textbox", { name: "编辑文件 main.ts" });
    fireEvent.change(editor, { target: { value: "conflicted draft" } });
    fireEvent.keyDown(editor, { key: "s", ctrlKey: true });
    await screen.findByText("外部冲突");
    expect(operations.saveFile).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "关闭 main.ts" }));
    const dialog = screen.getByRole("alertdialog", { name: "关闭未保存文件" });
    expect(dialog).toHaveTextContent("草稿与外部文件冲突");
    fireEvent.click(within(dialog).getByRole("button", { name: "取消" }));
    expect(operations.saveFile).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("tab", { name: /main\.ts/ })).toBeInTheDocument();
  });
});
