// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { WorkStepAdapter } from "@/features/conversation/domain/timelineTypes";
import { WorkProcess } from "@/features/conversation/ui/timeline/WorkProcess";
import { TimelineDisclosureCache } from "@/features/conversation/ui/timeline/timelineDisclosure";

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

  /** 未知 MCP 的可访问身份、首个目标和脱敏诊断正文必须在失败展开态同时可见。 */
  it("显示动作、真实 Tool 名称、首个目标并自动展开具体失败", () => {
    render(<WorkProcess steps={[toolStep()]} />);

    const trigger = screen.getByRole("button", { name: /调用工具，custom_mcp/u });
    expect(trigger).toBeVisible();
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(trigger).toHaveTextContent("调用工具");
    expect(trigger).toHaveTextContent('{"query":""}');
    expect(trigger.closest(".ja-tool-details")).toHaveAttribute("data-status", "error");
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

  /** grep 的固定脱敏行格式应该成为可扫描的命中列表，服务器摘要取代重复的机器发现尾注。 */
  it("将内容搜索结果渲染为紧凑命中列表", () => {
    const step = toolStep();
    const presentation = step.metadata?.presentation;
    if (presentation === undefined) throw new Error("test fixture presentation is missing");
    render(
      <WorkProcess
        steps={[
          {
            ...step,
            metadata: {
              ...step.metadata,
              toolName: "grep",
              presentation: {
                ...presentation,
                kind: "read",
                summary: "找到 2 个匹配项",
                outputPreview:
                  "src/App.tsx:12: const needle = true;\nsrc/App.tsx-13- export default App;",
              },
            },
          },
        ]}
      />,
    );

    expect(screen.getByText("找到 2 个匹配项")).toBeVisible();
    expect(screen.getByRole("list", { name: "搜索结果" })).toBeVisible();
    expect(screen.getAllByText("src/App.tsx").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("12")).toBeVisible();
    expect(screen.getByText("const needle = true;")).toBeVisible();
  });

  /** 成功 edit 只使用受控摘要说明结果规模，避免把执行端英文正文重复塞进工作过程。 */
  it("为成功编辑显示摘要而不重复输出正文", () => {
    const step = toolStep();
    const presentation = step.metadata?.presentation;
    if (presentation === undefined) throw new Error("test fixture presentation is missing");
    render(
      <WorkProcess
        steps={[
          {
            ...step,
            status: "completed",
            metadata: {
              ...step.metadata,
              toolName: "edit",
              presentation: {
                ...presentation,
                kind: "edit",
                status: "success",
                inputPreview: "edit src/App.tsx · 2 block(s)",
                outputPreview: "Successfully replaced 2 block(s) in the file.",
                summary: "已完成 2 处替换",
                relativePaths: ["src/App.tsx"],
              },
            },
          },
        ]}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "查看工作过程，已完成，1 步" }));
    const trigger = screen.getByRole("button", { name: /编辑，edit，src\/App\.tsx，完成/u });
    fireEvent.click(trigger);
    expect(screen.getByText("已完成 2 处替换")).toBeVisible();
    expect(
      screen.queryByText("Successfully replaced 2 block(s) in the file."),
    ).not.toBeInTheDocument();
  });

  /** Tool 展开选择使用 Item identity，流式转历史引起的虚拟卸载不能把用户正在查看的详情收回。 */
  it("跨工作过程卸载保留工具详情展开选择", () => {
    const cache = new TimelineDisclosureCache();
    const base = toolStep();
    const presentation = base.metadata?.presentation;
    if (presentation === undefined) throw new Error("test fixture presentation is missing");
    const step = {
      ...base,
      status: "completed" as const,
      metadata: {
        ...base.metadata,
        presentation: {
          ...presentation,
          status: "success" as const,
          summary: "工具调用完成",
          outputPreview: "稳定输出",
        },
      },
    };
    const first = render(
      <WorkProcess
        steps={[step]}
        disclosureCache={cache}
        disclosureKey="exchange:tool"
        disclosureThreadId="thr_tool_presentation"
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /查看工作过程/u }));
    const trigger = screen.getByRole("button", { name: /调用工具，custom_mcp/u });
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("稳定输出")).toBeVisible();
    first.unmount();

    render(
      <WorkProcess
        steps={[step]}
        disclosureCache={cache}
        disclosureKey="exchange:tool"
        disclosureThreadId="thr_tool_presentation"
      />,
    );
    expect(screen.getByRole("button", { name: /工作过程/u })).toHaveAttribute("data-state", "open");
    expect(screen.getByRole("button", { name: /调用工具，custom_mcp/u })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    expect(screen.getByText("稳定输出")).toBeVisible();
  });

  /** 已回答问答按题目成组展示，多选、自由输入和跳过都不能退化为内部结果 JSON。 */
  it("逐题显示多个问题与用户回答", () => {
    const step = toolStep();
    const presentation = step.metadata?.presentation;
    if (presentation === undefined) throw new Error("test fixture presentation is missing");
    render(
      <WorkProcess
        steps={[
          {
            ...step,
            status: "completed",
            metadata: {
              ...step.metadata,
              toolName: "request_user_input",
              presentation: {
                ...presentation,
                kind: "read",
                title: "User input",
                status: "success",
                inputPreview: "采用哪种配置范围？",
                outputPreview:
                  '[{"questionId":"question_scope","optionIds":["option_project"],"freeText":null,"skipped":false}]',
                summary: "已回答 3 个问题",
                interactionAnswers: [
                  {
                    question: "采用哪种同步策略？",
                    answers: ["保留本地改动", "合并远程提交"],
                    skipped: false,
                  },
                  {
                    question: "还需要注意什么？",
                    answers: ["不要覆盖未提交文件"],
                    skipped: false,
                  },
                  { question: "是否立即推送？", answers: [], skipped: true },
                ],
              },
            },
          },
        ]}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "查看工作过程，已完成，1 步" }));
    const trigger = screen.getByRole("button", { name: /询问用户，request_user_input/u });
    expect(trigger).toHaveTextContent("询问用户");
    expect(trigger).toHaveTextContent("已回答 3 个问题");
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("list", { name: "问答记录" })).toBeVisible();
    expect(screen.getByText("采用哪种同步策略？")).toBeVisible();
    expect(screen.getByText("保留本地改动")).toBeVisible();
    expect(screen.getByText("合并远程提交")).toBeVisible();
    expect(screen.getByText("不要覆盖未提交文件")).toBeVisible();
    expect(screen.getByText("已跳过")).toBeVisible();
    expect(screen.queryByText(/question_scope/u)).not.toBeInTheDocument();
    expect(screen.queryByText("状态：完成")).not.toBeInTheDocument();
  });
});
