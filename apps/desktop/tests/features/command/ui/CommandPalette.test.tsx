// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  CommandPalette,
  type CommandItemViewModel,
  type CommandPaletteActions,
} from "@/features/command";

const DEFAULT_COMMANDS: readonly CommandItemViewModel[] = [
  { id: "first", label: "打开设置", keywords: ["preferences"], running: false },
  { id: "second", label: "选择项目", keywords: ["project"], running: false },
];

/**
 * 以可控 viewModel/actions 驱动纯 UI，测试只验证 DOM 意图翻译，不在视图夹具中复制
 * application 的搜索或 single-flight 规则。
 */
function PaletteFixture({
  commands = DEFAULT_COMMANDS,
  execute = vi.fn(),
}: {
  commands?: readonly CommandItemViewModel[];
  execute?: (commandId: string) => void;
}): ReactElement {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [activeCommandId, setActiveCommandId] = useState(commands[0]?.id);
  const actions: CommandPaletteActions = {
    changeOpen: setOpen,
    changeQuery: setQuery,
    moveSelection: (delta) => {
      const current = Math.max(
        0,
        commands.findIndex((command) => command.id === activeCommandId),
      );
      const next = (current + delta + commands.length) % commands.length;
      setActiveCommandId(commands[next]?.id);
    },
    selectCommand: setActiveCommandId,
    executeCommand: execute,
  };
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        打开命令面板
      </button>
      <CommandPalette
        open={open}
        viewModel={{
          query,
          commands,
          ...(activeCommandId === undefined ? {} : { activeCommandId }),
          busy: commands.some((command) => command.running),
        }}
        actions={actions}
      />
    </>
  );
}

afterEach(() => {
  cleanup();
});

describe("CommandPalette UI", () => {
  it("exposes listbox semantics and translates keyboard navigation into narrow actions", async () => {
    const user = userEvent.setup();
    const execute = vi.fn();
    render(<PaletteFixture execute={execute} />);

    await user.click(screen.getByRole("button", { name: "打开命令面板" }));
    const input = screen.getByRole("textbox", { name: "搜索命令" });
    const list = screen.getByRole("listbox", { name: "可用命令" });
    expect(input).toHaveAttribute("aria-controls", list.id);
    expect(input).toHaveAttribute("aria-activedescendant", "ja-command-first");
    expect(screen.getAllByRole("option")).toHaveLength(2);

    await user.keyboard("{ArrowDown}{Enter}");
    expect(execute).toHaveBeenCalledWith("second");
  });

  it("does not emit execution intent while an IME composition is active", async () => {
    const user = userEvent.setup();
    const execute = vi.fn();
    render(<PaletteFixture execute={execute} />);

    await user.click(screen.getByRole("button", { name: "打开命令面板" }));
    const input = screen.getByRole("textbox", { name: "搜索命令" });
    await waitFor(() => expect(input).toHaveFocus());
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input, { key: "Enter", keyCode: 13, isComposing: true });
    expect(execute).not.toHaveBeenCalled();
    fireEvent.compositionEnd(input);
    await user.keyboard("{Enter}");
    expect(execute).toHaveBeenCalledWith("first");
  });

  it("restores focus to the invoking control after dismissal", async () => {
    const user = userEvent.setup();
    render(<PaletteFixture commands={[]} />);
    const opener = screen.getByRole("button", { name: "打开命令面板" });
    await user.click(opener);
    await user.click(screen.getByRole("button", { name: "关闭命令面板" }));
    await waitFor(() => expect(opener).toHaveFocus());
  });

  it("renders running commands as disabled without receiving their invoke callback", async () => {
    const user = userEvent.setup();
    const execute = vi.fn();
    render(
      <PaletteFixture
        commands={[{ id: "slow", label: "慢动作", keywords: [], running: true, icon: "command" }]}
        execute={execute}
      />,
    );
    await user.click(screen.getByRole("button", { name: "打开命令面板" }));
    expect(screen.getByRole("option", { name: "慢动作" })).toBeDisabled();
    expect(execute).not.toHaveBeenCalled();
  });
});
