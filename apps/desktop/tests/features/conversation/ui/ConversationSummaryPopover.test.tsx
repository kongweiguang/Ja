// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ConversationSummaryPopover,
  type ConversationSummary,
  type ConversationMcpStatusSnapshot,
} from "@/features/conversation";

afterEach(cleanup);

/** 所有展示用统计都由已提交事实提供；测试默认使用真正的空会话口径。 */
function summaryFixture(overrides: Partial<ConversationSummary> = {}): ConversationSummary {
  return {
    turnCount: 0,
    activity: {
      userMessages: 0,
      assistantMessages: 0,
      collaborationMessages: 0,
      totalMessages: 0,
      toolCalls: 0,
    },
    ...overrides,
  };
}

/** 构造真实清单的脱敏会话投影，未知数量始终留在后端。 */
function mcpSnapshot(
  threadId: string,
  source: ConversationMcpStatusSnapshot["source"],
  servers: ConversationMcpStatusSnapshot["servers"],
  notices: ConversationMcpStatusSnapshot["notices"] = [],
): ConversationMcpStatusSnapshot {
  return { threadId, source, servers, notices };
}
describe("ConversationSummaryPopover", () => {
  /** 分支属于标题摘要；没有真实分支时不渲染占位，避免 detached HEAD 被伪装成普通分支。 */
  it("只在摘要持有真实 Git 分支时展示分支信息", async () => {
    const user = userEvent.setup();
    const { rerender } = render(
      <ConversationSummaryPopover
        summary={summaryFixture({
          scope: "Ja",
          gitBranch: "main",
          runtime: "本地",
          turnCount: 1,
        })}
      />,
    );

    await user.click(screen.getByRole("button", { name: "打开上下文信息" }));
    const summary = screen.getByRole("dialog", { name: "会话概览" });
    expect(summary).toHaveTextContent("Git 分支");
    expect(summary).toHaveTextContent("main");

    await user.click(screen.getByRole("button", { name: "打开上下文信息" }));
    rerender(
      <ConversationSummaryPopover
        summary={summaryFixture({ scope: "无项目对话", runtime: "本地" })}
      />,
    );
    await user.click(screen.getByRole("button", { name: "打开上下文信息" }));
    expect(screen.getByRole("dialog", { name: "会话概览" })).not.toHaveTextContent("Git 分支");
    expect(screen.getByRole("dialog", { name: "会话概览" })).not.toHaveTextContent("模型选择");
  });

  /** 身份和路径复用原生复制端口，失败留在按钮上供键盘或鼠标重试。 */
  it("展示完整会话身份与统计，并对复制失败提供原位重试", async () => {
    const user = userEvent.setup();
    const rootPath = "\\\\?\\C:\\dev\\very-long-project-name\\deep-folder\\ja";
    const onCopyText = vi
      .fn()
      .mockRejectedValueOnce(new Error("clipboard unavailable"))
      .mockResolvedValue(undefined);
    render(
      <ConversationSummaryPopover
        summary={summaryFixture({
          scope: "Ja",
          model: "gpt-6-sol",
          activity: {
            userMessages: 2,
            assistantMessages: 3,
            collaborationMessages: 1,
            totalMessages: 6,
            toolCalls: 4,
          },
          turnCount: 2,
          durationMs: 65_000,
        })}
        threadId="thr_current"
        threadTitle="一个长会话"
        createdAt="2026-09-23T06:00:00Z"
        rootPath={rootPath}
        onCopyText={onCopyText}
      />,
    );

    await user.click(screen.getByRole("button", { name: "打开上下文信息" }));
    const dialog = screen.getByRole("dialog", { name: "会话概览" });
    expect(dialog).toHaveTextContent("一个长会话");
    expect(dialog).toHaveTextContent("文字消息");
    expect(dialog).toHaveTextContent("用户 2 · 助手 3 · 协作 1");
    expect(dialog).toHaveTextContent("累计处理时间");
    expect(dialog).toHaveTextContent("1 分 5 秒");
    expect(dialog).toHaveTextContent("C:\\dev\\very-long-project-name\\deep-folder\\ja");
    expect(dialog).not.toHaveTextContent("\\\\?\\C:");
    expect(dialog).toHaveTextContent("gpt-6-sol");

    await user.click(screen.getByRole("button", { name: "复制会话 ID" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "复制会话 ID失败，重试" })).toBeInTheDocument(),
    );
    expect(screen.getByRole("button", { name: "复制会话 ID失败，重试" })).toHaveFocus();
    await user.keyboard("{Enter}");
    await waitFor(() => expect(screen.getByRole("button", { name: "已复制" })).toBeInTheDocument());
    expect(onCopyText).toHaveBeenNthCalledWith(1, "thr_current");
    expect(onCopyText).toHaveBeenNthCalledWith(2, "thr_current");
    await user.click(screen.getByRole("button", { name: "复制工作目录" }));
    expect(onCopyText).toHaveBeenNthCalledWith(
      3,
      "C:\\dev\\very-long-project-name\\deep-folder\\ja",
    );
  });

  /** 概览直接列出同名的全局和项目服务，且隐藏期间不产生读取。 */
  it("直接列出名称、来源与状态", async () => {
    const user = userEvent.setup();
    const mcpReader = {
      read: vi.fn(async () =>
        mcpSnapshot("thr_current", "active", [
          { serverId: "mcp_global", name: "Kerminal", scope: "global", state: "available" },
          { serverId: "mcp_project", name: "Kerminal", scope: "project", state: "disabled" },
          {
            serverId: "mcp_pending",
            name: "项目测试服务",
            scope: "project",
            state: "not_discovered",
          },
        ]),
      ),
    };
    const onOpenMcpSettings = vi.fn();
    render(
      <ConversationSummaryPopover
        summary={summaryFixture()}
        threadId="thr_current"
        mcpReader={mcpReader}
        onOpenMcpSettings={onOpenMcpSettings}
      />,
    );
    expect(mcpReader.read).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "打开上下文信息" }));
    const list = await screen.findByRole("list", { name: "当前会话的 MCP 服务" });
    expect(within(list).getAllByText("Kerminal")).toHaveLength(2);
    expect(within(list).getAllByText("当前项目")).toHaveLength(2);
    expect(within(list).getByText("全局")).toBeInTheDocument();
    expect(within(list).getByText("本轮可用")).toBeInTheDocument();
    expect(within(list).getByText("已停用")).toBeInTheDocument();
    expect(within(list).getByText("未检查")).toBeInTheDocument();
    expect(screen.queryByText(/个工具/)).toBeNull();
    expect(screen.queryByRole("button", { name: "检查连接" })).toBeNull();
    expect(mcpReader.read).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "管理 MCP" }));
    expect(onOpenMcpSettings).toHaveBeenCalledTimes(1);
  });

  /** 读取失败保留可重试入口，配置变化只提示当前 Turn 的下轮边界。 */
  it("读取失败可重试且展示配置变化提示", async () => {
    const user = userEvent.setup();
    const mcpReader = {
      read: vi
        .fn()
        .mockRejectedValueOnce(new Error("offline"))
        .mockResolvedValue(
          mcpSnapshot(
            "thr_current",
            "active",
            [{ serverId: "mcp_kerminal", name: "Kerminal", scope: "global", state: "available" }],
            ["configuration_changed"],
          ),
        ),
    };
    render(
      <ConversationSummaryPopover
        summary={summaryFixture()}
        threadId="thr_current"
        mcpReader={mcpReader}
      />,
    );
    await user.click(screen.getByRole("button", { name: "打开上下文信息" }));
    expect(await screen.findByText("MCP 状态读取失败，请重试。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("配置已更新，下轮生效")).toBeInTheDocument();
    expect(mcpReader.read).toHaveBeenCalledTimes(2);
  });
});
