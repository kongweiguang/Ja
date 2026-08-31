// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { CodeEditor, CodeViewer, DiffViewer } from "@/features/workbench/editor";

afterEach(() => cleanup());

/** 为无布局能力的 jsdom Range 提供 CodeMirror 所需最小几何合同，避免异步 Decoration Measurement 产生噪声。 */
beforeAll(() => {
  Object.defineProperty(Range.prototype, "getClientRects", {
    configurable: true,
    value: () => [] as unknown as DOMRectList,
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
