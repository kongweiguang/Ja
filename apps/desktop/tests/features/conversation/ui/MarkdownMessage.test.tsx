// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MarkdownMessage } from "@/features/conversation/ui/timeline/MarkdownMessage";

afterEach(() => cleanup());

describe("MarkdownMessage destinations", () => {
  it("opens explicit unknown-extension Markdown links as file buttons with line locations", async () => {
    const onOpenFile = vi.fn();
    render(
      <MarkdownMessage content="[custom file](./asset.scene#L12C3)" onOpenFile={onOpenFile} />,
    );

    const button = screen.getByRole("button", { name: "在 Ja 中打开文件 ./asset.scene" });
    expect(button).toHaveAttribute("data-file-reference", "./asset.scene");
    expect(button.closest("a")).toBeNull();
    await userEvent.click(button);
    expect(onOpenFile).toHaveBeenCalledWith({ path: "./asset.scene", line: 12, column: 3 }, button);
  });

  it("decodes local Markdown paths with spaces, Chinese names, and file URLs", async () => {
    const onOpenFile = vi.fn();
    render(
      <MarkdownMessage
        content={
          "[relative](./文档%20空间.txt#L12) [absolute](file:///C:/Projects/测试%20目录/a%20b.svg#L4C2) [drive](C:/Projects/测试%20目录/main.ts#L3C4) [share](file://server/share/测试%20文档.md#L9)"
        }
        onOpenFile={onOpenFile}
      />,
    );

    await userEvent.click(screen.getByRole("button", { name: "在 Ja 中打开文件 ./文档 空间.txt" }));
    await userEvent.click(
      screen.getByRole("button", { name: "在 Ja 中打开文件 C:/Projects/测试 目录/a b.svg" }),
    );
    await userEvent.click(
      screen.getByRole("button", { name: "在 Ja 中打开文件 C:/Projects/测试 目录/main.ts" }),
    );
    await userEvent.click(
      screen.getByRole("button", { name: "在 Ja 中打开文件 \\\\server\\share\\测试 文档.md" }),
    );
    expect(onOpenFile).toHaveBeenNthCalledWith(
      1,
      { path: "./文档 空间.txt", line: 12 },
      expect.any(HTMLElement),
    );
    expect(onOpenFile).toHaveBeenNthCalledWith(
      2,
      { path: "C:/Projects/测试 目录/a b.svg", line: 4, column: 2 },
      expect.any(HTMLElement),
    );
    expect(onOpenFile).toHaveBeenNthCalledWith(
      3,
      { path: "C:/Projects/测试 目录/main.ts", line: 3, column: 4 },
      expect.any(HTMLElement),
    );
    expect(onOpenFile).toHaveBeenNthCalledWith(
      4,
      { path: "\\\\server\\share\\测试 文档.md", line: 9 },
      expect.any(HTMLElement),
    );
  });

  it("opens web links only through an href-free button, never browser default navigation", async () => {
    const onOpenLink = vi.fn();
    render(<MarkdownMessage content="[site](https://example.test/path)" onOpenLink={onOpenLink} />);

    const button = screen.getByRole("button", {
      name: "在 Ja 浏览器中打开 https://example.test/path",
    });
    expect(button).not.toHaveAttribute("href");
    expect(button.closest("a")).toBeNull();
    fireEvent(button, new MouseEvent("auxclick", { bubbles: true, button: 1 }));
    fireEvent.contextMenu(button);
    expect(onOpenLink).not.toHaveBeenCalled();
    await userEvent.click(button);
    expect(onOpenLink).toHaveBeenCalledWith("https://example.test/path", button);
  });

  it("keeps JavaScript and data URLs inert", () => {
    const onOpenLink = vi.fn();
    const onOpenFile = vi.fn();
    render(
      <MarkdownMessage
        content="[script](javascript:alert(1)) [payload](data:text/html,hello) [evil.txt](javascript:alert(2))"
        onOpenLink={onOpenLink}
        onOpenFile={onOpenFile}
      />,
    );

    expect(screen.getByText("script").closest("a")).toBeNull();
    expect(screen.getByText("payload").closest("a")).toBeNull();
    expect(screen.getByText("evil.txt").closest("a")).toBeNull();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(onOpenLink).not.toHaveBeenCalled();
    expect(onOpenFile).not.toHaveBeenCalled();
  });

  it("links old inline path references with Windows-safe line and column syntax", async () => {
    const onOpenFile = vi.fn();
    const path = String.raw`C:\repo\src\App.tsx`;
    render(<MarkdownMessage content={`Open \`${path}:12:3\`.`} onOpenFile={onOpenFile} />);

    const button = screen.getByRole("button", { name: `在 Ja 中打开文件 ${path}` });
    await userEvent.click(button);
    expect(onOpenFile).toHaveBeenCalledWith({ path, line: 12, column: 3 }, button);
  });

  /** 新旧回复共用修饰键语义，不改变普通点击已有的两个参数合同。 */
  it("routes Ctrl+click on both Markdown links and old inline paths to Explorer", () => {
    const onOpenFile = vi.fn();
    const path = String.raw`C:\outside\old.txt`;
    render(
      <MarkdownMessage
        content={`[new](./中文%20文件.txt#L8) and \`${path}:3\``}
        onOpenFile={onOpenFile}
      />,
    );
    const explicit = screen.getByRole("button", { name: "在 Ja 中打开文件 ./中文 文件.txt" });
    const inline = screen.getByRole("button", { name: `在 Ja 中打开文件 ${path}` });
    expect(explicit).toHaveAttribute("title", expect.stringContaining("Ctrl+点击"));
    fireEvent.click(explicit, { ctrlKey: true });
    fireEvent.click(inline, { ctrlKey: true });
    expect(onOpenFile).toHaveBeenNthCalledWith(
      1,
      { path: "./中文 文件.txt", line: 8 },
      explicit,
      "explorer",
    );
    expect(onOpenFile).toHaveBeenNthCalledWith(2, { path, line: 3 }, inline, "explorer");
  });

  /** 键盘用户获得同等的原生显示动作，普通 Enter 仍只打开 Ja 文件页。 */
  it("routes Ctrl+Enter through the Explorer path without also opening a Ja tab", async () => {
    const onOpenFile = vi.fn();
    render(
      <MarkdownMessage content="[查看](./文档.txt) 和 `./旧文档.txt`" onOpenFile={onOpenFile} />,
    );
    const explicit = screen.getByRole("button", { name: "在 Ja 中打开文件 ./文档.txt" });
    const inline = screen.getByRole("button", { name: "在 Ja 中打开文件 ./旧文档.txt" });
    expect(explicit).toHaveAttribute("aria-keyshortcuts", "Control+Enter");
    expect(inline).toHaveAttribute("aria-description", expect.stringContaining("Ctrl+Enter"));
    explicit.focus();
    await userEvent.keyboard("{Control>}{Enter}{/Control}");
    inline.focus();
    await userEvent.keyboard("{Control>}{Enter}{/Control}");
    expect(onOpenFile).toHaveBeenCalledTimes(2);
    expect(onOpenFile).toHaveBeenNthCalledWith(1, { path: "./文档.txt" }, explicit, "explorer");
    expect(onOpenFile).toHaveBeenNthCalledWith(2, { path: "./旧文档.txt" }, inline, "explorer");
  });

  it("does not turn paths inside fenced code blocks into links", () => {
    render(<MarkdownMessage content={"```text\nsrc/main.ts\n```"} onOpenFile={vi.fn()} />);

    expect(screen.getByText("src/main.ts")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
