// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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

  /** ContextMenu 键打开 Radix 菜单，Escape 由共享层关闭并恢复搜索行焦点。 */
  it("supports the keyboard context-menu key and restores focus on Escape", async () => {
    const user = userEvent.setup();
    const result = { id: "r1", path: "main.ts", line: 1, preview: "match" };
    render(<SearchPanel query="main" results={[result]} onAddToConversation={vi.fn()} />);
    const trigger = screen.getByRole("button", { name: /main\.ts/ });

    trigger.focus();
    fireEvent.keyDown(trigger, { key: "ContextMenu" });
    const menu = await screen.findByRole("menu", { name: "main.ts 文件操作" });
    expect(menu).toHaveFocus();
    await user.keyboard("{Escape}");
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  /** 同一结果 id 在刷新后可能承载新投影，菜单必须调用最新对象而非旧闭包。 */
  it("opens the current result from its context menu after a search refresh", async () => {
    const user = userEvent.setup();
    const onOpenResult = vi.fn();
    const original = { id: "r1", path: "src/App.tsx", line: 1, preview: "old" };
    const current = { ...original, path: "src/Renamed.tsx", line: 3, preview: "new" };
    const { rerender } = render(
      <SearchPanel query="App" results={[original]} onOpenResult={onOpenResult} />,
    );

    await user.pointer({
      keys: "[MouseRight]",
      target: screen.getByRole("button", { name: /src\/App\.tsx/ }),
    });
    expect(await screen.findByRole("menu", { name: "src/App.tsx 文件操作" })).toBeVisible();
    rerender(<SearchPanel query="Renamed" results={[current]} onOpenResult={onOpenResult} />);
    expect(await screen.findByRole("menu", { name: "src/Renamed.tsx 文件操作" })).toBeVisible();
    expect(screen.queryByRole("menuitem", { name: "添加到对话" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("menuitem", { name: "打开" }));
    expect(onOpenResult).toHaveBeenCalledOnce();
    expect(onOpenResult).toHaveBeenCalledWith(current);
  });

  /** capability 消失时旧菜单立即收起，不让仍挂载的目标继续提交操作。 */
  it("closes a search context menu when its stable result id disappears", async () => {
    const user = userEvent.setup();
    const onAddToConversation = vi.fn();
    const result = { id: "r1", path: "main.ts", line: 1, preview: "match" };
    const { rerender } = render(
      <SearchPanel query="match" results={[result]} onAddToConversation={onAddToConversation} />,
    );

    await user.pointer({
      keys: "[MouseRight]",
      target: screen.getByRole("button", { name: /main\.ts/ }),
    });
    expect(await screen.findByRole("menu", { name: "main.ts 文件操作" })).toBeVisible();
    rerender(<SearchPanel query="match" results={[]} onAddToConversation={onAddToConversation} />);

    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(onAddToConversation).not.toHaveBeenCalled();
  });

  /** 新的右键对象必须取代旧目标与锚点，不能让菜单点击回写上一行。 */
  it("retargets the search context menu to the most recently right-clicked result", async () => {
    const user = userEvent.setup();
    const onAddToConversation = vi.fn();
    const result = { id: "r1", path: "src/first.ts", line: 1, preview: "first" };
    render(
      <SearchPanel query="src" results={[result]} onAddToConversation={onAddToConversation} />,
    );

    const resultButton = screen.getByRole("button", { name: /src\/first\.ts/ });
    fireEvent.contextMenu(resultButton, { clientX: 18, clientY: 22 });
    expect(await screen.findByRole("menu", { name: "src/first.ts 文件操作" })).toBeVisible();
    fireEvent.contextMenu(resultButton, { clientX: 42, clientY: 58 });
    expect(await screen.findByRole("menu", { name: "src/first.ts 文件操作" })).toBeVisible();
    expect(document.querySelector(".ja-pointer-context-anchor")).toHaveStyle({
      left: "42px",
      top: "58px",
    });

    await user.click(screen.getByRole("menuitem", { name: "添加到对话" }));
    expect(onAddToConversation).toHaveBeenCalledWith(result);
  });
});
