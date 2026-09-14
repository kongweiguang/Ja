// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { WorkStepAdapter } from "@/features/conversation/domain/timelineTypes";
import { WorkProcess } from "@/features/conversation/ui/timeline/WorkProcess";

const turnId = "turn_tool_presentation";

/** 构造最小真实 Tool Item，让断言覆盖 WorkProcess 到 ToolStepDetails 的完整渲染链。 */
function toolStep(overrides: Partial<WorkStepAdapter> = {}): WorkStepAdapter {
  return {
    itemId: "item_tool_presentation",
    threadId: "thr_tool_presentation",
    turnId,
    kind: "tool_call",
    status: "failed",
    metadata: {
      callId: "call_custom_mcp",
      toolName: "custom_mcp",
      presentation: {
        kind: "mcp",
        title: "外部工具",
        status: "error",
        inputPreview: '{"query":""}',
        outputPreview: "query 不能为空",
        relativePaths: [],
        truncated: false,
      },
    },
    ...overrides,
  };
}

describe("ToolStepDetails", () => {
  afterEach(() => cleanup());

  /** 未知 MCP 真实名称、首个目标和脱敏诊断正文必须在失败展开态同时可见。 */
  it("显示动作、真实 Tool 名称、首个目标并自动展开具体失败", () => {
    render(<WorkProcess steps={[toolStep()]} />);

    const trigger = screen.getByRole("button", { name: /调用工具，custom_mcp/u });
    expect(trigger).toBeVisible();
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(trigger).toHaveTextContent("调用工具");
    expect(trigger).toHaveTextContent("custom_mcp");
    expect(trigger).toHaveTextContent('{"query":""}');
    expect(screen.getByText("query 不能为空")).toBeVisible();
    expect(screen.queryByText("调用工具 .")).not.toBeInTheDocument();
  });

  /** 新内建文件工具必须按真实名称呈现明确动作，避免 grep/find/ls 被误认成泛化 MCP 调用。 */
  it.each([
    ["grep", "搜索内容"],
    ["find", "查找文件"],
    ["ls", "列出目录"],
    ["read_attachment", "读取附件"],
  ])("为 %s 显示 %s 动作", (toolName, action) => {
    const step = toolStep();
    render(<WorkProcess steps={[{ ...step, metadata: { ...step.metadata, toolName } }]} />);

    expect(
      screen.getByRole("button", { name: new RegExp(`${action}，${toolName}`, "u") }),
    ).toBeVisible();
  });

  /** 搜索工具的折叠入口必须优先显示安全 query/pattern，而不是只显示搜索目录。 */
  it.each([
    ["grep", "搜索内容", 'query="needle" · src'],
    ["find", "查找文件", 'pattern="*.tsx" · src'],
  ])("为 %s 显示首个搜索目标", (toolName, action, target) => {
    const base = toolStep();
    const presentation = base.metadata?.presentation;
    if (presentation === undefined) throw new Error("test fixture presentation is missing");
    render(
      <WorkProcess
        steps={[
          {
            ...base,
            status: "in_progress",
            metadata: {
              ...base.metadata,
              toolName,
              presentation: {
                ...presentation,
                kind: "read",
                title: action,
                status: "running",
                inputPreview: target,
                relativePaths: ["src"],
              },
            },
          },
        ]}
      />,
    );

    expect(
      screen.getByRole("button", { name: `${action}，${toolName}，${target}，进行中` }),
    ).toBeVisible();
  });
});
