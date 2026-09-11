// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ReviewUnifiedDiff,
  type ReviewUnifiedDiffFile,
  type ReviewUnifiedDiffLine,
} from "@/features/workbench/review/ui/ReviewUnifiedDiff";

const uiFontSizeState = vi.hoisted(() => ({ value: 16 }));

vi.mock("@/shared/hooks/useInterfacePreferencesValue", () => ({
  useUiFontSize: () => uiFontSizeState.value,
}));

afterEach(() => {
  cleanup();
  uiFontSizeState.value = 16;
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

interface SyntaxWorkerHarness {
  readonly postMessage: ReturnType<typeof vi.fn>;
  readonly terminate: ReturnType<typeof vi.fn>;
  reply(lines: readonly unknown[]): void;
}

/** 安装手动响应的 Worker，证明首帧纯文本不依赖 parser 完成且异步 token 只更新当前文件。 */
function installSyntaxWorker(): SyntaxWorkerHarness[] {
  const workers: SyntaxWorkerHarness[] = [];
  vi.stubGlobal(
    "Worker",
    class {
      readonly postMessage = vi.fn();
      readonly terminate = vi.fn();
      readonly listeners = new Map<string, Set<(event: MessageEvent) => void>>();

      constructor() {
        workers.push(this as unknown as SyntaxWorkerHarness);
      }

      addEventListener(type: string, listener: (event: MessageEvent) => void): void {
        const listeners = this.listeners.get(type) ?? new Set();
        listeners.add(listener);
        this.listeners.set(type, listeners);
      }

      removeEventListener(type: string, listener: (event: MessageEvent) => void): void {
        this.listeners.get(type)?.delete(listener);
      }

      reply(lines: readonly unknown[]): void {
        const requestId = this.postMessage.mock.calls.at(-1)?.[0].requestId as number;
        for (const listener of this.listeners.get("message") ?? [])
          listener({ data: { requestId, lines } } as MessageEvent);
      }
    },
  );
  return workers;
}

/** 高亮会拆成多个 span，断言整行 code 文本而不依赖内部 token 数量。 */
function codeText(text: string): (_content: string, element: Element | null) => boolean {
  return (_content, element) => element?.tagName === "CODE" && element.textContent === text;
}

/** 构造行号连续的上下文，测试只关注 viewer 投影而不重复解析 unified 文本。 */
function contextLines(start: number, end: number, prefix: string): ReviewUnifiedDiffLine[] {
  return Array.from({ length: end - start + 1 }, (_, offset) => ({
    kind: "context" as const,
    oldLine: start + offset,
    newLine: start + offset,
    text: `${prefix}-${start + offset}`,
  }));
}

/** 构造两个 native hunk，确保折叠、导航和未加载缺口都能被独立观察。 */
function structuredFile(): ReviewUnifiedDiffFile {
  return {
    path: "src/main.rs",
    lines: [
      { kind: "deletion", oldLine: 1, newLine: null, text: "old-a" },
      { kind: "addition", oldLine: null, newLine: 1, text: "new-a" },
      ...contextLines(2, 9, "a-context"),
      { kind: "deletion", oldLine: 10, newLine: null, text: "old-b" },
      { kind: "addition", oldLine: null, newLine: 10, text: "new-b" },
      { kind: "deletion", oldLine: 20, newLine: null, text: "old-c" },
      { kind: "addition", oldLine: null, newLine: 20, text: "new-c" },
      ...contextLines(21, 28, "b-context"),
      { kind: "deletion", oldLine: 29, newLine: null, text: "old-d" },
      { kind: "addition", oldLine: null, newLine: 29, text: "new-d" },
    ],
    hunks: [
      {
        hunkId: "first",
        header: "@@ -1,10 +1,10 @@ first",
        oldStart: 1,
        oldLines: 10,
        newStart: 1,
        newLines: 10,
      },
      {
        hunkId: "second",
        header: "@@ -20,10 +20,10 @@ second",
        oldStart: 20,
        oldLines: 10,
        newStart: 20,
        newLines: 10,
      },
    ],
  };
}

describe("ReviewUnifiedDiff", () => {
  it("统一模式默认只显示该变更所在一侧的单个语义行号", () => {
    const file: ReviewUnifiedDiffFile = {
      path: "single.ts",
      lines: [
        { kind: "deletion", oldLine: 7, newLine: null, text: "old" },
        { kind: "addition", oldLine: null, newLine: 9, text: "new" },
        { kind: "context", oldLine: 8, newLine: 10, text: "context" },
      ],
    };
    const { container } = render(<ReviewUnifiedDiff file={file} revision="unified" />);
    const viewer = screen.getByRole("region", { name: "统一 Diff single.ts" });

    expect(viewer).toHaveAttribute("data-review-diff-mode", "unified");
    const rows = container.querySelectorAll(".ja-review-unified-diff-row.is-line");
    expect(rows).toHaveLength(3);
    expect(rows[0]?.querySelectorAll(".ja-review-unified-diff-number")).toHaveLength(1);
    expect(rows[0]?.querySelector(".ja-review-unified-diff-number")).toHaveTextContent("7");
    expect(rows[0]?.querySelector(".ja-review-unified-diff-number")).toHaveAttribute(
      "title",
      "旧文件第 7 行",
    );
    expect(rows[1]?.querySelector(".ja-review-unified-diff-number")).toHaveTextContent("9");
    expect(rows[1]?.querySelector(".ja-review-unified-diff-number")).toHaveAccessibleName(
      "新文件第 9 行",
    );
    expect(rows[2]?.querySelector(".ja-review-unified-diff-number")).toHaveTextContent("10");
  });

  it("双栏模式按结构化增删块配对并保留空侧与双侧上下文", () => {
    const file: ReviewUnifiedDiffFile = {
      path: "split.ts",
      lines: [
        { kind: "deletion", oldLine: 4, newLine: null, text: "old-a" },
        { kind: "deletion", oldLine: 5, newLine: null, text: "old-b" },
        { kind: "addition", oldLine: null, newLine: 4, text: "new-a" },
        { kind: "context", oldLine: 6, newLine: 5, text: "shared" },
      ],
    };
    const { container } = render(
      <ReviewUnifiedDiff file={file} revision="split" viewMode="split" />,
    );
    const viewer = screen.getByRole("region", { name: "双栏 Diff split.ts" });

    expect(viewer).toHaveAttribute("data-review-diff-mode", "split");
    const rows = container.querySelectorAll(".ja-review-unified-diff-row.is-split-line");
    expect(rows).toHaveLength(3);
    expect(rows[0]?.querySelector('[data-review-diff-side="old"] code')).toHaveTextContent("old-a");
    expect(rows[0]?.querySelector('[data-review-diff-side="new"] code')).toHaveTextContent("new-a");
    expect(rows[1]?.querySelector('[data-review-diff-side="old"] code')).toHaveTextContent("old-b");
    expect(rows[1]?.querySelector('[data-review-diff-side="new"]')).toHaveAccessibleName(
      "新文件无对应行",
    );
    expect(rows[2]?.querySelector('[data-review-diff-side="old"] code')).toHaveTextContent(
      "shared",
    );
    expect(rows[2]?.querySelector('[data-review-diff-side="new"] code')).toHaveTextContent(
      "shared",
    );
    expect(
      rows[2]?.querySelector('[data-review-diff-side="old"] .ja-review-unified-diff-number'),
    ).toHaveTextContent("6");
    expect(
      rows[2]?.querySelector('[data-review-diff-side="new"] .ja-review-unified-diff-number'),
    ).toHaveTextContent("5");
  });

  it("切换双栏不重复高亮请求，并复用上下文折叠与 hunk 操作", async () => {
    const workers = installSyntaxWorker();
    const user = userEvent.setup();
    const file = structuredFile();
    const renderHunkActions = vi.fn((hunk: { hunkId?: string }) => (
      <button type="button">操作 {hunk.hunkId}</button>
    ));
    const rendered = render(
      <ReviewUnifiedDiff
        file={file}
        revision="shared"
        viewMode="unified"
        renderHunkActions={renderHunkActions}
      />,
    );
    await waitFor(() => expect(workers).toHaveLength(1));
    expect(workers[0]!.postMessage).toHaveBeenCalledTimes(1);

    rendered.rerender(
      <ReviewUnifiedDiff
        file={file}
        revision="shared"
        viewMode="split"
        renderHunkActions={renderHunkActions}
      />,
    );
    expect(workers[0]!.postMessage).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "操作 first" })).toBeVisible();
    await user.click(screen.getAllByRole("button", { name: "展开 2 行上下文" })[0]!);
    expect(screen.getAllByText(codeText("a-context-5"))).toHaveLength(2);
    expect(screen.getAllByText(codeText("a-context-6"))).toHaveLength(2);
  });

  it("先显示完整纯文本，再异步接纳 Files 语法 token", async () => {
    const workers = installSyntaxWorker();
    const file: ReviewUnifiedDiffFile = {
      path: "main.ts",
      lines: [
        { kind: "deletion", oldLine: 1, newLine: null, text: "/* old comment" },
        { kind: "deletion", oldLine: 2, newLine: null, text: "old content */" },
        { kind: "addition", oldLine: null, newLine: 1, text: 'const value = "new";' },
      ],
    };
    const { container } = render(<ReviewUnifiedDiff file={file} revision="syntax-1" />);
    const added = container.querySelector('[data-review-diff-row-kind="addition"]');
    expect(added).toHaveTextContent('const value = "new";');
    expect(added?.querySelector("[data-syntax-role]")).not.toBeInTheDocument();
    await waitFor(() => expect(workers).toHaveLength(1));
    expect(workers[0]!.postMessage).toHaveBeenCalledTimes(1);
    expect(container.querySelector("[data-review-unified-diff]")).toHaveAttribute(
      "data-review-syntax",
      "loading",
    );
    await act(async () =>
      workers[0]!.reply([
        [{ text: "/* old comment", role: "comment" }],
        [{ text: "old content */", role: "comment" }],
        [
          { text: "const", role: "keyword" },
          { text: " value = " },
          { text: '"new"', role: "string" },
          { text: ";" },
        ],
      ]),
    );
    const keyword = added?.querySelector<HTMLElement>('[data-syntax-role="keyword"]');
    expect(keyword).toHaveTextContent("const");
    expect(keyword?.style.color).toBe("var(--ja-syntax-keyword)");
    expect(added?.querySelector('[data-syntax-role="string"]')).toHaveTextContent('"new"');
    expect(
      container.querySelector(
        '[data-review-diff-row-kind="deletion"] [data-syntax-role="comment"]',
      ),
    ).toBeInTheDocument();
    expect(container.querySelector("[data-review-unified-diff]")).toHaveAttribute(
      "data-review-syntax",
      "ready",
    );
  });

  it("隐藏父 Tab 不创建 Worker，重新可见后创建且卸载时终止", async () => {
    const workers = installSyntaxWorker();
    const host = document.createElement("div");
    host.setAttribute("data-tab-panel", "review");
    host.hidden = true;
    document.body.append(host);
    const rendered = render(
      <ReviewUnifiedDiff
        file={{
          path: "hidden.ts",
          lines: [{ kind: "addition", oldLine: null, newLine: 1, text: "const hidden = 1;" }],
        }}
        revision="hidden-revision"
      />,
      { container: host },
    );
    await act(async () => undefined);
    expect(workers).toHaveLength(0);

    host.hidden = false;
    await waitFor(() => expect(workers).toHaveLength(1));
    rendered.unmount();
    await waitFor(() => expect(workers[0]!.terminate).toHaveBeenCalledTimes(1));
    host.remove();
  });

  it("保持 native hunk 边界，并且不为未加载缺口提供伪展开", () => {
    const { container } = render(<ReviewUnifiedDiff file={structuredFile()} revision="rev-1" />);

    expect(screen.getByText("@@ -1,10 +1,10 @@ first")).toBeVisible();
    expect(screen.getByText("@@ -20,10 +20,10 @@ second")).toBeVisible();
    expect(container.querySelectorAll('[data-review-diff-row-kind="hunk"]')).toHaveLength(2);
    expect(container.querySelectorAll('[data-review-diff-row-kind="gap"]')).toHaveLength(1);
    expect(screen.getByLabelText("未加载的文件内容")).toBeVisible();
    expect(screen.queryByRole("button", { name: /未加载/u })).not.toBeInTheDocument();
  });

  it("每段长上下文独立展开和收起，并始终保留变更两侧三行", async () => {
    const user = userEvent.setup();
    render(<ReviewUnifiedDiff file={structuredFile()} revision="rev-1" />);

    expect(screen.getByText(codeText("a-context-2"))).toBeVisible();
    expect(screen.getByText(codeText("a-context-4"))).toBeVisible();
    expect(screen.queryByText(codeText("a-context-5"))).not.toBeInTheDocument();
    expect(screen.queryByText(codeText("b-context-24"))).not.toBeInTheDocument();
    const toggles = screen.getAllByRole("button", { name: "展开 2 行上下文" });
    await user.click(toggles[0]!);
    expect(screen.getByText(codeText("a-context-5"))).toBeVisible();
    expect(screen.getByText(codeText("a-context-6"))).toBeVisible();
    expect(screen.queryByText(codeText("b-context-24"))).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "折叠 2 行上下文" }));
    expect(screen.queryByText(codeText("a-context-5"))).not.toBeInTheDocument();
  });

  it("缺少 hunk 元数据时按结构化行号缺口分区，不会跨缺口串接", () => {
    const file: ReviewUnifiedDiffFile = {
      path: "src/turn.ts",
      lines: [
        { kind: "deletion", oldLine: 2, newLine: null, text: "before" },
        { kind: "addition", oldLine: null, newLine: 2, text: "after" },
        { kind: "deletion", oldLine: 90, newLine: null, text: "distant-before" },
        { kind: "addition", oldLine: null, newLine: 90, text: "distant-after" },
      ],
    };
    const { container } = render(<ReviewUnifiedDiff file={file} revision="rev-1" />);

    expect(container.querySelectorAll('[data-review-diff-row-kind="hunk"]')).toHaveLength(2);
    expect(container.querySelectorAll('[data-review-diff-row-kind="gap"]')).toHaveLength(1);
    expect(screen.getByText("旧行 2 · 新行 2")).toBeVisible();
    expect(screen.getByText("旧行 90 · 新行 90")).toBeVisible();
  });

  it("提供键盘可达的上下区块导航和稳定 E2E selector", async () => {
    const user = userEvent.setup();
    const { container } = render(<ReviewUnifiedDiff file={structuredFile()} revision="rev-1" />);
    const viewer = container.querySelector<HTMLElement>("[data-review-unified-diff]");
    expect(viewer).toHaveAttribute("data-review-diff-path", "src/main.rs");
    expect(screen.getByText("1 / 2")).toBeVisible();

    const next = screen.getByRole("button", { name: "下一个变更区块" });
    next.focus();
    await user.keyboard("{Enter}");
    expect(next).toHaveFocus();
    expect(screen.getByText("2 / 2")).toBeVisible();
    expect(screen.getByRole("button", { name: "上一个变更区块" })).toBeEnabled();
  });

  it("只为原生 hunk 渲染上下文操作，并按 revision 隔离浏览状态", async () => {
    const user = userEvent.setup();
    const file = structuredFile();
    const renderHunkActions = vi.fn((hunk: { hunkId?: string }) => (
      <button type="button">操作 {hunk.hunkId}</button>
    ));
    const rendered = render(
      <ReviewUnifiedDiff file={file} revision="rev-1" renderHunkActions={renderHunkActions} />,
    );
    expect(screen.getByRole("button", { name: "操作 first" })).toBeVisible();
    expect(renderHunkActions).toHaveBeenCalledWith(expect.objectContaining({ hunkId: "first" }), 0);
    expect(renderHunkActions).toHaveBeenCalledWith(
      expect.objectContaining({ hunkId: "second" }),
      1,
    );

    await user.click(screen.getAllByRole("button", { name: "展开 2 行上下文" })[0]!);
    expect(screen.getByText(codeText("a-context-5"))).toBeVisible();
    rendered.rerender(
      <ReviewUnifiedDiff file={file} revision="rev-2" renderHunkActions={renderHunkActions} />,
    );
    expect(screen.queryByText(codeText("a-context-5"))).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "展开 2 行上下文" })).toHaveLength(2);
  });

  it("仅在真实复制能力存在时复制完整原生 Diff", async () => {
    const onCopyText = vi.fn(async () => undefined);
    const file = { ...structuredFile(), unified: "@@ native @@\n-old\n+new" };
    const rendered = render(
      <ReviewUnifiedDiff file={file} revision="rev-1" onCopyText={onCopyText} />,
    );
    await userEvent.setup().click(screen.getByRole("button", { name: "复制 Diff" }));
    await waitFor(() => expect(onCopyText).toHaveBeenCalledWith(file.unified));

    rendered.rerender(<ReviewUnifiedDiff file={file} revision="rev-1" />);
    expect(screen.queryByRole("button", { name: "复制 Diff" })).not.toBeInTheDocument();
  });

  it("一万行输入只物化虚拟窗口内的 DOM 行", () => {
    const lines: ReviewUnifiedDiffLine[] = Array.from({ length: 10_000 }, (_, index) => ({
      kind: index % 2 === 0 ? "deletion" : "addition",
      oldLine: index % 2 === 0 ? index / 2 + 1 : null,
      newLine: index % 2 === 0 ? null : (index + 1) / 2,
      text: `line-${index}`,
    }));
    const { container } = render(
      <ReviewUnifiedDiff file={{ path: "large.txt", lines }} revision="rev-large" />,
    );
    const viewer = container.querySelector("[data-review-unified-diff]");
    expect(Number(viewer?.getAttribute("data-review-row-count"))).toBeGreaterThan(9_000);
    expect(
      container.querySelectorAll('[data-review-diff-row-kind="addition"]').length,
    ).toBeLessThan(100);

    const viewport = container.querySelector<HTMLElement>(".ja-review-unified-diff-viewport");
    expect(viewport).not.toBeNull();
    fireEvent.scroll(viewport!, { target: { scrollTop: 4_000 } });
    expect(container.querySelectorAll(".ja-review-unified-diff-row").length).toBeLessThan(150);
  });

  it("界面字号变化时同步虚拟 Diff 行高，避免 CSS 行盒与滚动偏移不一致", async () => {
    const file: ReviewUnifiedDiffFile = {
      path: "scaled.ts",
      lines: [{ kind: "addition", oldLine: null, newLine: 1, text: "const scaled = true;" }],
    };
    const rendered = render(<ReviewUnifiedDiff file={file} revision="scaled" />);
    const { container } = rendered;
    const spacer = container.querySelector<HTMLElement>(".ja-review-unified-diff-spacer");
    expect(spacer).not.toBeNull();
    const defaultHeight = Number.parseFloat(spacer!.style.height);

    uiFontSizeState.value = 18;
    rendered.rerender(<ReviewUnifiedDiff file={file} revision="scaled" />);
    await waitFor(() =>
      expect(Number.parseFloat(spacer!.style.height)).toBeCloseTo((defaultHeight * 18) / 16, 4),
    );
  });
});
