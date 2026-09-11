// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { EditorView } from "@codemirror/view";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { CodeEditor, CodeViewer, DiffViewer } from "@/features/workbench/editor";

const themeMock = vi.hoisted(() => ({
  resolvedTheme: "light" as "light" | "dark",
  palette: "xcode" as "xcode" | "ja" | "jetbrains" | "obsidian" | "claude",
}));

vi.mock("@/shared/hooks/useResolvedTheme", () => ({
  useResolvedTheme: () => themeMock.resolvedTheme,
  useUiPalette: () => themeMock.palette,
}));

afterEach(() => {
  cleanup();
  themeMock.resolvedTheme = "light";
  themeMock.palette = "xcode";
});

/** 为无布局能力的 jsdom Range 提供 CodeMirror 所需最小几何合同，避免异步 Decoration Measurement 产生噪声。 */
beforeAll(() => {
  Object.defineProperty(Range.prototype, "getClientRects", {
    configurable: true,
    value: () => [] as unknown as DOMRectList,
  });
});

/** 同屏挂载三类编辑器，用稳定 DOM identity 验证主题 reconfigure 不触发实例重建。 */
function ThemeEditorsFixture(): React.ReactElement {
  return (
    <div>
      <CodeEditor filePath="src/edit.ts" content={'const edited: string = "yes";'} />
      <CodeViewer filePath="src/view.ts" content={'const viewed: string = "yes";'} />
      <DiffViewer
        filePath="src/diff.ts"
        original={'const changed: string = "before";'}
        modified={'const changed: string = "after";'}
      />
    </div>
  );
}

describe("CodeMirror semantic theme", () => {
  /** 新语法必须穿过真实组件并产生高亮 DOM，浅深切换不能只保留空 parser 配置。 */
  it.each([
    ["main.py", 'def hello():\n  return "hello"'],
    ["config.yaml", 'name: "hello"'],
    ["index.html", '<div class="hello">Hello</div>'],
    ["script.ps1", '$name = "hello"'],
  ])("renders syntax spans in editors and viewers for %s", (filePath, content) => {
    for (const mode of ["light", "dark"] as const) {
      themeMock.resolvedTheme = mode;
      const rendered = render(
        <>
          <CodeEditor filePath={filePath} content={content} />
          <CodeViewer filePath={filePath} content={content} />
        </>,
      );
      const editors = rendered.container.querySelectorAll(".cm-editor");
      expect(editors).toHaveLength(2);
      for (const editor of editors) {
        expect(editor).toHaveAttribute("data-ja-code-theme", `xcode-${mode}`);
        expect(editor.querySelectorAll(".cm-line span[class]").length).toBeGreaterThan(0);
        expect(editor.querySelector(".cm-content")?.textContent).toContain("hello");
      }
      rendered.unmount();
    }
  });

  /** 十种视觉组合都只能重配 extension，不得重建 View、文档或 selection。 */
  it("hot-switches every palette and mode while preserving editor state", () => {
    const rendered = render(<ThemeEditorsFixture />);
    const editorElements = Array.from(
      rendered.container.querySelectorAll<HTMLElement>(".cm-editor"),
    );
    const editorViews = editorElements.map((editor) => EditorView.findFromDOM(editor));
    const documents = editorViews.map((view) => view?.state.doc.toString());
    const anchors = editorViews.map((view, index) => {
      const anchor = Math.min(index + 1, view?.state.doc.length ?? 0);
      view?.dispatch({ selection: { anchor } });
      return anchor;
    });
    expect(editorViews).toHaveLength(4);
    expect(editorViews.every((view) => view !== null)).toBe(true);

    for (const palette of ["xcode", "ja", "jetbrains", "obsidian", "claude"] as const) {
      for (const mode of ["light", "dark"] as const) {
        themeMock.palette = palette;
        themeMock.resolvedTheme = mode;
        rendered.rerender(<ThemeEditorsFixture />);
        Array.from(rendered.container.querySelectorAll<HTMLElement>(".cm-editor")).forEach(
          (editor, index) => {
            const view = EditorView.findFromDOM(editor);
            expect(editor).toBe(editorElements[index]);
            expect(editor).toHaveAttribute("data-ja-code-theme", `${palette}-${mode}`);
            expect(view).toBe(editorViews[index]);
            expect(view?.state.doc.toString()).toBe(documents[index]);
            expect(view?.state.selection.main.anchor).toBe(anchors[index]);
          },
        );
      }
    }
  });

  /** 浅深模式都必须挂载同一套语义高亮角色，不能让浅色退回 CodeMirror 默认配色。 */
  it("mounts the shared semantic syntax token rules for light and dark code", () => {
    const rendered = render(
      <CodeViewer
        filePath="src/theme.ts"
        content={'const ready: string = "yes"; // semantic comment'}
      />,
    );

    expect(rendered.container.querySelector(".cm-editor")).toHaveAttribute(
      "data-ja-code-theme",
      "xcode-light",
    );
    expect(rendered.container.querySelectorAll(".cm-line span").length).toBeGreaterThan(0);
    const mountedStyles = Array.from(document.querySelectorAll("style"))
      .map((style) => style.textContent ?? "")
      .join("\n");
    expect(mountedStyles).toContain("var(--ja-syntax-keyword)");
    expect(mountedStyles).toContain("var(--ja-syntax-string)");
    expect(mountedStyles).toContain("var(--ja-syntax-comment)");

    themeMock.palette = "claude";
    themeMock.resolvedTheme = "dark";
    rendered.rerender(
      <CodeViewer
        filePath="src/theme.ts"
        content={'const ready: string = "yes"; // semantic comment'}
      />,
    );
    expect(rendered.container.querySelector(".cm-editor")).toHaveAttribute(
      "data-ja-code-theme",
      "claude-dark",
    );
  });
});

describe("CodeMirror read-only viewers", () => {
  it("updates an external revision and destroys the editor on unmount", () => {
    const rendered = render(
      <CodeViewer filePath="src/App.tsx" content="const before = true;" revision={1} />,
    );
    expect(rendered.container.querySelectorAll(".cm-editor")).toHaveLength(1);
    rendered.rerender(
      <CodeViewer filePath="src/App.tsx" content="const after = true;" revision={2} />,
    );
    expect(rendered.container.querySelector(".cm-content")?.textContent).toContain("after");
    rendered.unmount();
    expect(rendered.container.querySelectorAll(".cm-editor")).toHaveLength(0);
  });

  it("starts a fresh document when the selected file changes", () => {
    const rendered = render(<CodeViewer filePath="src/one.ts" content="const one = 1;" />);
    rendered.rerender(<CodeViewer filePath="src/two.rs" content="let two = 2;" />);
    expect(rendered.container.querySelector(".cm-content")?.textContent).toContain("two");
    expect(rendered.container.querySelector(".cm-content")?.textContent).not.toContain("one");
  });

  it("uses upstream MergeView and cleans both editor sides", () => {
    const rendered = render(
      <DiffViewer
        filePath="src/main.rs"
        original="let old = 1;"
        modified="let new = 2;"
        revision="diff-1"
      />,
    );
    expect(rendered.container.querySelectorAll(".cm-editor")).toHaveLength(2);
    expect(rendered.container.querySelector(".cm-mergeView")).toBeInTheDocument();
    rendered.unmount();
    expect(rendered.container.querySelectorAll(".cm-editor")).toHaveLength(0);
  });

  it("copies code and the explicit two-sided Diff through the host writer", async () => {
    const user = userEvent.setup();
    const onCopyText = vi.fn(async () => undefined);
    const rendered = render(
      <CodeViewer filePath="src/main.rs" content="let ready = true;" onCopyText={onCopyText} />,
    );
    await user.click(screen.getByRole("button", { name: "复制代码" }));
    expect(onCopyText).toHaveBeenCalledWith("let ready = true;");

    rendered.rerender(
      <DiffViewer
        filePath="src/main.rs"
        original="let ready = false;"
        modified="let ready = true;"
        onCopyText={onCopyText}
      />,
    );
    await user.click(screen.getByRole("button", { name: "复制 Diff" }));
    expect(onCopyText).toHaveBeenLastCalledWith(
      "文件：src/main.rs\n\n--- 原始内容\nlet ready = false;\n\n+++ 修改后内容\nlet ready = true;",
    );
  });
});

describe("CodeMirror writable editor", () => {
  it("forwards drafts, Ctrl+S and blur without rebuilding the view", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onSave = vi.fn();
    const onBlur = vi.fn();
    render(
      <CodeEditor
        filePath="src/main.ts"
        content="const main = true;"
        onChange={onChange}
        onSave={onSave}
        onBlur={onBlur}
      />,
    );
    const editor = screen.getByRole("textbox", { name: "编辑文件 src/main.ts" });
    await user.click(editor);
    await user.type(editor, "\nconst next = true;");
    expect(onChange).toHaveBeenLastCalledWith(expect.stringContaining("const next = true;"));
    await user.keyboard("{Control>}s{/Control}");
    expect(onSave).toHaveBeenCalledOnce();
    editor.dispatchEvent(new FocusEvent("blur", { bubbles: true }));
    expect(onBlur).toHaveBeenCalled();
  });
});
