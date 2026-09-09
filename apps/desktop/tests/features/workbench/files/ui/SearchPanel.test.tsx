// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SearchPanel } from "@/features/workbench/files/ui/SearchPanel";

afterEach(cleanup);

describe("SearchPanel", () => {
  it("forwards query changes and opens a virtualized result", async () => {
    const user = userEvent.setup();
    const onQueryChange = vi.fn();
    const onOpenResult = vi.fn();
    const result = {
      id: "r1",
      path: "src/App.tsx",
      line: 12,
      column: 4,
      preview: "const answer = true",
      matchStart: 6,
      matchLength: 6,
    };
    render(
      <SearchPanel results={[result]} onQueryChange={onQueryChange} onOpenResult={onOpenResult} />,
    );
    const input = screen.getByRole("searchbox", { name: "搜索工作区" });
    await user.type(input, "answer");
    expect(onQueryChange).toHaveBeenLastCalledWith("answer");
    await user.click(screen.getByRole("button", { name: /src\/App\.tsx/ }));
    expect(onOpenResult).toHaveBeenCalledWith(result);
    expect(screen.getByText("answer")).toBeInTheDocument();
  });

  it("shows the file tree content directly while the unified filter is empty", () => {
    render(<SearchPanel results={[]} idleContent={<div>文件树</div>} />);
    expect(screen.getByText("文件树")).toBeInTheDocument();
  });

  it("discloses bounded native search statistics instead of claiming complete results", () => {
    render(
      <SearchPanel
        query="match"
        results={[{ id: "r1", path: "main.ts", line: 1, preview: "match" }]}
        summary={{ truncated: true, scannedEntries: 2_000, skippedFiles: 3 }}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent(
      "1 个结果 · 已扫描 2000 个条目 · 跳过 3 个文件 · 结果已达到安全上限，可能不完整",
    );
  });

  it("reuses shared feedback semantics for loading and retryable errors", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = render(<SearchPanel query="match" results={[]} loading />);
    expect(screen.getByRole("status")).toHaveTextContent("正在搜索…");

    rerender(
      <SearchPanel query="match" results={[]} error="搜索服务暂不可用。" onRetry={onRetry} />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("搜索失败");
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("adds a search result to the conversation from the context menu", async () => {
    const user = userEvent.setup();
    const onAddToConversation = vi.fn();
    const result = { id: "r1", path: "src/App.tsx", line: 1, preview: "match" };
    render(
      <SearchPanel query="App" results={[result]} onAddToConversation={onAddToConversation} />,
    );

    await user.pointer({
      keys: "[MouseRight]",
      target: screen.getByRole("button", { name: /src\/App\.tsx/ }),
    });
    await user.click(screen.getByRole("menuitem", { name: "添加到对话" }));
    expect(onAddToConversation).toHaveBeenCalledWith(result);
  });
});
