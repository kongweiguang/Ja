// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ConversationRenameDialog,
  ConversationSearchDialog,
  type ThreadProjection,
} from "@/features/navigation";

const THREADS: readonly ThreadProjection[] = [
  {
    threadId: "thread_recent",
    title: "迁移方案",
    status: "active",
  },
  {
    threadId: "thread_ui",
    title: "桌面界面优化",
    status: "active",
  },
];

describe("ConversationSearchDialog", () => {
  afterEach(() => cleanup());

  it("空查询展示最近会话，并支持命中高亮和 Enter 打开", async () => {
    const user = userEvent.setup();
    const onSearch = vi.fn(async (query: string) => (query === "界面" ? [THREADS[1]!] : THREADS));
    const onSelect = vi.fn();
    const onOpenChange = vi.fn();
    render(
      <ConversationSearchDialog
        open
        shortcutLabel="Ctrl+K"
        onOpenChange={onOpenChange}
        onSearch={onSearch}
        onSelect={onSelect}
      />,
    );

    expect(await screen.findByText("最近会话")).toBeVisible();
    expect(screen.getByText("Ctrl+K")).toBeVisible();
    expect(await screen.findByRole("option", { name: "迁移方案" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    const input = screen.getByRole("searchbox", { name: "搜索对话" });
    await user.clear(input);
    await user.type(input, "界面");
    const result = await screen.findByRole("option");
    expect(result).toHaveTextContent("桌面界面优化");
    expect(result.querySelector("mark")).toHaveTextContent("界面");
    await user.keyboard("{Enter}");
    expect(onOpenChange).toHaveBeenCalledWith(false);
    expect(onSelect).toHaveBeenCalledWith("thread_ui");
  });

  it("空结果的方向键保持稳定，IME Enter 不会选择旧结果", async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(
      <ConversationSearchDialog
        open
        shortcutLabel="Ctrl+K"
        onOpenChange={vi.fn()}
        onSearch={async (query) => (query === "无" ? [] : THREADS)}
        onSelect={onSelect}
      />,
    );
    const input = screen.getByRole("searchbox", { name: "搜索对话" });
    await screen.findByRole("option", { name: "迁移方案" });
    await user.clear(input);
    await user.type(input, "无");
    await screen.findByText("没有匹配的会话");
    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(input).not.toHaveAttribute("aria-activedescendant");
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("长结果键盘导航时保持活动项可见且不移动输入焦点", async () => {
    const longResults = Array.from(
      { length: 18 },
      (_, index): ThreadProjection => ({
        threadId: `thread_${index}`,
        title: `会话 ${index + 1}`,
        status: "active",
      }),
    );
    const scrollIntoView = vi.spyOn(HTMLElement.prototype, "scrollIntoView");
    render(
      <ConversationSearchDialog
        open
        shortcutLabel="Ctrl+K"
        onOpenChange={vi.fn()}
        onSearch={async () => longResults}
        onSelect={vi.fn()}
      />,
    );

    const input = screen.getByRole("searchbox", { name: "搜索对话" });
    await screen.findByRole("option", { name: "会话 18" });
    scrollIntoView.mockClear();
    fireEvent.keyDown(input, { key: "End" });

    await waitFor(() =>
      expect(scrollIntoView).toHaveBeenLastCalledWith({ block: "nearest", inline: "nearest" }),
    );
    expect(scrollIntoView.mock.contexts.at(-1)).toBe(
      screen.getByRole("option", { name: "会话 18" }),
    );
    expect(input).toHaveFocus();
  });

  it("标题元数据变化时静默刷新当前查询并按 Thread 保留选择与焦点", async () => {
    const searchable: readonly ThreadProjection[] = [
      { threadId: "thread_a", title: "会话 A", status: "active" },
      { threadId: "thread_b", title: "会话 B", status: "active" },
    ];
    let resolveRefresh: ((items: readonly ThreadProjection[]) => void) | undefined;
    const refreshResult = new Promise<readonly ThreadProjection[]>((resolve) => {
      resolveRefresh = resolve;
    });
    const onSearch = vi
      .fn<(query: string) => Promise<readonly ThreadProjection[]>>()
      .mockResolvedValueOnce(searchable)
      .mockResolvedValueOnce(searchable)
      .mockImplementationOnce(() => refreshResult)
      .mockRejectedValueOnce(new Error("refresh unavailable"));
    const props = {
      open: true,
      shortcutLabel: "Ctrl+K",
      onOpenChange: vi.fn(),
      onSearch,
      onSelect: vi.fn(),
    } as const;
    const { rerender } = render(<ConversationSearchDialog {...props} />);

    const input = screen.getByRole("searchbox", { name: "搜索对话" });
    await screen.findByRole("option", { name: "会话 B" });
    fireEvent.change(input, { target: { value: "会话" } });
    await waitFor(() => expect(onSearch).toHaveBeenLastCalledWith("会话"));
    await screen.findByRole("option", { name: "会话 B" });
    fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(screen.getByRole("option", { name: "会话 B" })).toHaveAttribute("aria-selected", "true");

    rerender(<ConversationSearchDialog {...props} refreshIdentity="evt_title_1" />);
    await waitFor(() => expect(onSearch).toHaveBeenCalledTimes(3));
    expect(input).toHaveValue("会话");
    expect(input).toHaveFocus();
    expect(screen.queryByLabelText("正在搜索")).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: "会话 B" })).toBeVisible();

    await act(async () => {
      resolveRefresh?.([
        { threadId: "thread_b", title: "会话 B 新标题", status: "active" },
        { threadId: "thread_a", title: "会话 A 新标题", status: "active" },
      ]);
      await refreshResult;
    });
    expect(screen.getByRole("option", { name: "会话 B 新标题" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(input).toHaveFocus();

    rerender(<ConversationSearchDialog {...props} refreshIdentity="evt_title_2" />);
    await waitFor(() => expect(onSearch).toHaveBeenCalledTimes(4));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("正在搜索")).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: "会话 B 新标题" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(input).toHaveValue("会话");
    expect(input).toHaveFocus();
  });
});

describe("ConversationRenameDialog", () => {
  afterEach(() => cleanup());

  it("服务端完成后才关闭，并提交去除两端空白的人工标题", async () => {
    const user = userEvent.setup();
    let resolveRename: (() => void) | undefined;
    const onRename = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          resolveRename = resolve;
        }),
    );
    const onOpenChange = vi.fn();
    render(
      <ConversationRenameDialog
        thread={THREADS[0]}
        open
        onOpenChange={onOpenChange}
        onRename={onRename}
      />,
    );

    const input = screen.getByRole("textbox", { name: "会话标题" });
    await user.clear(input);
    await user.type(input, "  人工标题  ");
    await user.click(screen.getByRole("button", { name: "保存" }));
    expect(onRename).toHaveBeenCalledWith("thread_recent", "人工标题");
    expect(onOpenChange).not.toHaveBeenCalled();
    resolveRename?.();
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
  });

  it("失败时保留输入并允许重试，IME Enter 不会提前提交", async () => {
    const user = userEvent.setup();
    const onRename = vi
      .fn<(threadId: string, title: string) => Promise<void>>()
      .mockRejectedValueOnce(new Error("conflict"))
      .mockResolvedValueOnce();
    render(
      <ConversationRenameDialog
        thread={THREADS[1]}
        open
        onOpenChange={vi.fn()}
        onRename={onRename}
      />,
    );
    const input = screen.getByRole("textbox", { name: "会话标题" });
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    expect(onRename).not.toHaveBeenCalled();
    fireEvent.compositionEnd(input);
    await user.click(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("重命名失败");
    expect(input).toHaveValue("桌面界面优化");
    await user.click(screen.getByRole("button", { name: "保存" }));
    expect(onRename).toHaveBeenCalledTimes(2);
  });
});
