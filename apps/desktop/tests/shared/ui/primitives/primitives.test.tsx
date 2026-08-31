// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Button } from "@/shared/ui/primitives/Button";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/shared/ui/primitives/Collapsible";
import { ErrorState, LoadingState } from "@/shared/ui/primitives/Feedback";
import { IconButton } from "@/shared/ui/primitives/IconButton";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
  DialogTrigger,
} from "@/shared/ui/primitives/Dialog";
import { Menu, MenuContent, MenuItem, MenuTrigger } from "@/shared/ui/primitives/Menu";
import { ScrollArea } from "@/shared/ui/primitives/ScrollArea";
import { GroupedSelect, Select } from "@/shared/ui/primitives/Select";

/** 用受控状态覆盖公共浮层的真实开闭和焦点事务，不在测试中直接组合 Radix。 */
function FloatingSurfaceFixture({ onMenuSelect }: { onMenuSelect: () => void }): ReactElement {
  const [dialogOpen, setDialogOpen] = useState(false);
  return (
    <>
      <Menu>
        <MenuTrigger asChild>
          <button type="button">打开共享菜单</button>
        </MenuTrigger>
        <MenuContent aria-label="共享菜单">
          <MenuItem onSelect={onMenuSelect}>执行动作</MenuItem>
        </MenuContent>
      </Menu>
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogTrigger asChild>
          <button type="button">打开共享对话框</button>
        </DialogTrigger>
        <DialogContent overlayClassName="fixture-dialog-overlay">
          <DialogTitle>共享对话框</DialogTitle>
          <DialogDescription>公共焦点与 Portal 契约</DialogDescription>
          <DialogClose>关闭</DialogClose>
        </DialogContent>
      </Dialog>
    </>
  );
}

describe("accessible primitives", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });
  it("keeps loading controls disabled and exposes busy state", () => {
    render(<Button loading>保存</Button>);
    expect(screen.getByRole("button")).toBeDisabled();
    expect(screen.getByRole("button")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("button")).toHaveTextContent("处理中");
  });

  it("supports keyboard disclosure through Radix", async () => {
    const user = userEvent.setup();
    render(
      <Collapsible>
        <CollapsibleTrigger>详情</CollapsibleTrigger>
        <CollapsibleContent>输出</CollapsibleContent>
      </Collapsible>,
    );
    const trigger = screen.getByRole("button", { name: "详情" });
    await user.click(trigger);
    expect(trigger).toHaveAttribute("data-state", "open");
    expect(screen.getByText("输出")).toBeVisible();
  });

  /** Menu wrapper 必须同时提供 Portal、共享主题类、键盘选择和关闭后的焦点恢复。 */
  it("keeps menu portal, keyboard, and focus behavior inside the shared layer", async () => {
    const user = userEvent.setup();
    const onMenuSelect = vi.fn();
    const { container } = render(<FloatingSurfaceFixture onMenuSelect={onMenuSelect} />);
    const opener = screen.getByRole("button", { name: "打开共享菜单" });

    await user.click(opener);
    const menu = screen.getByRole("menu");
    expect(container.querySelector('[role="menu"]')).not.toBeInTheDocument();
    expect(menu).toHaveAttribute("aria-label", "共享菜单");
    expect(menu).toHaveClass("ja-floating-surface", "ja-menu-content");
    await user.keyboard("{ArrowDown}{Enter}");
    expect(onMenuSelect).toHaveBeenCalledOnce();
    expect(opener).toHaveFocus();

    await user.click(opener);
    await user.keyboard("{Escape}");
    expect(opener).toHaveFocus();
  });

  /** Dialog wrapper 必须让 feature 无法遗漏 Overlay、Portal 与 Radix 的 Escape 焦点事务。 */
  it("owns dialog overlay, portal, Escape, and focus restoration", async () => {
    const user = userEvent.setup();
    const { container } = render(<FloatingSurfaceFixture onMenuSelect={vi.fn()} />);
    const opener = screen.getByRole("button", { name: "打开共享对话框" });

    await user.click(opener);
    const dialog = screen.getByRole("dialog", { name: "共享对话框" });
    expect(container.querySelector('[role="dialog"]')).not.toBeInTheDocument();
    expect(dialog).toHaveClass("ja-floating-surface", "ja-dialog-content");
    expect(document.querySelector(".fixture-dialog-overlay")).toHaveClass("ja-dialog-overlay");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "共享对话框" })).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  /** 单层与分组选择共享一个 Portal/focus 契约，业务调用方只处理稳定 value。 */
  it("provides keyboard-ready plain and grouped selects on the shared floating surface", async () => {
    const user = userEvent.setup();
    const onPlainChange = vi.fn();
    const onGroupedChange = vi.fn();
    render(
      <>
        <Select
          ariaLabel="审批模式"
          ariaDescribedBy="approval-mode-hint"
          ariaInvalid
          value="approval"
          options={[
            { value: "approval", label: "需要审批" },
            { value: "full", label: "完全访问" },
          ]}
          onValueChange={onPlainChange}
        />
        <span id="approval-mode-hint">发送时冻结到当前轮次</span>
        <GroupedSelect
          ariaLabel="模型"
          value="openai:gpt"
          groups={[
            {
              label: "OpenAI",
              options: [{ value: "openai:gpt", label: "GPT" }],
            },
            {
              label: "DeepSeek",
              options: [{ value: "deepseek:chat", label: "DeepSeek Chat" }],
            },
          ]}
          onValueChange={onGroupedChange}
        />
      </>,
    );

    screen.getByRole("combobox", { name: "审批模式" }).focus();
    expect(screen.getByRole("combobox", { name: "审批模式" })).toHaveAttribute(
      "aria-describedby",
      "approval-mode-hint",
    );
    expect(screen.getByRole("combobox", { name: "审批模式" })).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    await user.keyboard("{Enter}{ArrowDown}{Enter}");
    expect(onPlainChange).toHaveBeenCalledWith("full");

    screen.getByRole("combobox", { name: "模型" }).focus();
    await user.keyboard("{Enter}");
    expect(screen.getByText("OpenAI")).toBeVisible();
    expect(screen.getByText("DeepSeek")).toBeVisible();
    expect(screen.getByRole("listbox").closest(".ja-floating-surface")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.getByRole("combobox", { name: "模型" })).toHaveFocus();
    expect(onGroupedChange).not.toHaveBeenCalled();
  });

  /** 强制一个可访问名称同时驱动按钮与 Tooltip，避免不同 feature 维护两份文案。 */
  it("为图标动作统一可访问名称与 Tooltip", async () => {
    const user = userEvent.setup();
    render(
      <>
        <IconButton label="刷新文件树" tooltip={false} title="刷新">
          <span aria-hidden="true">↻</span>
        </IconButton>
        <IconButton label="创建" tooltip="创建新文件">
          <span aria-hidden="true">+</span>
        </IconButton>
      </>,
    );

    const button = screen.getByRole("button", { name: "刷新文件树" });
    const tooltipButton = screen.getByRole("button", { name: "创建" });
    expect(button).toHaveAttribute("title", "刷新");
    expect(tooltipButton).not.toHaveAttribute("title");
    await user.hover(screen.getByRole("button", { name: "创建" }));

    expect(button).toHaveAccessibleName("刷新文件树");
    expect(await screen.findByRole("tooltip")).toHaveTextContent("创建新文件");
  });

  /** 同一用户路径覆盖 loading 投影与 error 重试，避免公共状态在不同 feature 间语义漂移。 */
  it("为通用异步状态提供稳定语义和可聚焦重试动作", async () => {
    const user = userEvent.setup();
    const retry = vi.fn();
    const { rerender } = render(<LoadingState label="正在打开终端…" />);

    expect(screen.getByRole("status")).toHaveTextContent("正在打开终端…");

    rerender(<ErrorState message="终端加载失败" onRetry={retry} />);
    const retryButton = screen.getByRole("button", { name: "重试" });
    retryButton.focus();
    expect(retryButton).toHaveFocus();
    await user.keyboard("{Enter}");

    expect(screen.getByRole("alert")).toHaveTextContent("终端加载失败");
    expect(retry).toHaveBeenCalledTimes(1);
  });

  /** 公共 ScrollArea 固定 Root/Viewport 契约，滚动条显隐继续由 Radix 的真实溢出测量决定。 */
  it("为非虚拟化长面板提供统一滚动 viewport", () => {
    const { container } = render(
      <ScrollArea className="settings-scroll">
        <p>设置内容</p>
      </ScrollArea>,
    );

    expect(container.querySelector(".ja-scroll-area.settings-scroll")).toBeInTheDocument();
    expect(container.querySelector("[data-radix-scroll-area-viewport]")).toHaveClass(
      "ja-scroll-area-viewport",
    );
    expect(container.querySelector(".ja-scroll-area-viewport")).toHaveTextContent("设置内容");
  });
});
