// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { ConversationSummaryPopover } from "@/features/conversation";

afterEach(cleanup);

describe("ConversationSummaryPopover", () => {
  /** 分支属于标题摘要；没有真实分支时不渲染占位，避免 detached HEAD 被伪装成普通分支。 */
  it("只在摘要持有真实 Git 分支时展示分支信息", async () => {
    const user = userEvent.setup();
    const { rerender } = render(
      <ConversationSummaryPopover
        summary={{ scope: "Ja", gitBranch: "main", runtime: "本地", turnCount: 1, stepCount: 2 }}
      />,
    );

    await user.click(screen.getByRole("button", { name: "打开对话摘要" }));
    const summary = screen.getByRole("dialog", { name: "环境信息" });
    expect(summary).toHaveTextContent("分支");
    expect(summary).toHaveTextContent("main");

    await user.click(screen.getByRole("button", { name: "打开对话摘要" }));
    rerender(
      <ConversationSummaryPopover
        summary={{ scope: "无项目对话", runtime: "本地", turnCount: 0, stepCount: 0 }}
      />,
    );
    await user.click(screen.getByRole("button", { name: "打开对话摘要" }));
    expect(screen.getByRole("dialog", { name: "环境信息" })).not.toHaveTextContent("分支");
  });
});
