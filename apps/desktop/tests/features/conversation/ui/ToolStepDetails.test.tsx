// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
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

/** 构造带恢复 CAS 的原工具详情；测试只经 WorkProcess 传入真实 Turn 投影，不伪造组件内部状态。 */
function recoveryToolStep(callId = "call_recovery"): WorkStepAdapter {
  const base = toolStep();
  const presentation = base.metadata?.presentation;
  if (presentation === undefined) throw new Error("test fixture presentation is missing");
  return {
    ...base,
    itemId: `item_${callId}`,
    turnId: "turn_recovery",
    threadId: "thr_recovery",
    metadata: {
      ...base.metadata,
      callId,
      toolName: "write_file",
      presentation: {
        ...presentation,
        kind: "write",
        title: "写入文件",
        status: "running",
        command: "write_file retry.txt",
        recovery: { revision: 7 },
      },
    },
  };
}

describe("ToolStepDetails", () => {
  afterEach(() => cleanup());

  /** 未知 MCP 的失败详情默认收起，但用户展开后仍能查看脱敏诊断正文。 */
  it("显示动作、真实 Tool 名称和首个目标并按需查看失败详情", () => {
    render(<WorkProcess steps={[toolStep()]} />);

    const trigger = screen.getByRole("button", { name: /调用工具，custom_mcp/u });
    expect(trigger).toBeVisible();
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveTextContent("调用工具");
    expect(trigger).toHaveTextContent('{"query":""}');
    expect(trigger.closest(".ja-tool-details")).toHaveAttribute("data-status", "error");
    fireEvent.click(trigger);
    expect(screen.getByText("query 不能为空")).toBeVisible();
    expect(screen.queryByText("调用工具 .")).not.toBeInTheDocument();
  });

  /** 失败的 shell 命令仍沿用普通命令的紧凑入口，用户需要时再手动查看诊断输出。 */
  it("失败的 shell 命令保持收起", () => {
    const base = toolStep();
    const presentation = base.metadata?.presentation;
    if (presentation === undefined) throw new Error("test fixture presentation is missing");
    render(
      <WorkProcess
        steps={[
          {
            ...base,
            metadata: {
              ...base.metadata,
              toolName: "shell",
              presentation: {
                ...presentation,
                kind: "shell",
                title: "执行命令",
                status: "error",
                command: "pnpm test",
                outputPreview: "command failed",
                stderr: "command failed",
              },
            },
          },
        ]}
      />,
    );

    const trigger = screen.getByRole("button", { name: /执行命令，shell，pnpm test，失败/u });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
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

  /** grep 的固定脱敏行格式应该成为可扫描的命中列表，服务器摘要取代重复的机器发现尾注；详情按需展开。 */
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

    fireEvent.click(screen.getByRole("button", { name: /搜索内容，grep/u }));
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

  /**
   * 裁决请求必须携带渲染时的双 revision 与稳定幂等键；重复点击在 Promise 未结算前只提交一次，
   * 不让前端乐观修改 Timeline 或重复触发可能有副作用的重试。
   */
  it("冻结恢复 CAS 身份并阻止重新执行的重复点击", async () => {
    let release: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => {
      release = resolve;
    });
    const resolveRecovery = vi.fn(async () => pending);
    render(
      <WorkProcess
        steps={[recoveryToolStep()]}
        turn={{
          turnId: "turn_recovery",
          threadId: "thr_recovery",
          status: "suspended",
          threadRevision: 19,
        }}
        onResolveToolRecovery={resolveRecovery}
      />,
    );

    const retry = screen.getByRole("button", { name: "重新执行" });
    fireEvent.click(retry);
    fireEvent.click(retry);

    await waitFor(() => expect(resolveRecovery).toHaveBeenCalledTimes(1));
    expect(resolveRecovery).toHaveBeenCalledWith(
      expect.objectContaining({
        threadId: "thr_recovery",
        turnId: "turn_recovery",
        callId: "call_recovery",
        expectedThreadRevision: 19,
        expectedRecoveryRevision: 7,
        decision: "retry",
        idempotencyKey: expect.stringMatching(/^recovery-[0-9a-f]{8}$/u),
      }),
    );
    expect(retry).toBeDisabled();
    release?.();
  });

  /**
   * 旧详情发起中的请求仍保留旧 Tool/revision 身份；随后切换到新 Thread 不会把它改写为新页面的
   * 裁决。最终是否接受由 Runtime 与 Java 的 revision CAS 拒绝，组件不伪造成功状态。
   */
  it("任务切换后保留原恢复请求身份且不处理新工具", async () => {
    let release: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => {
      release = resolve;
    });
    const resolveRecovery = vi.fn(async () => pending);
    const rendered = render(
      <WorkProcess
        steps={[recoveryToolStep("call_old_recovery")]}
        turn={{
          turnId: "turn_recovery",
          threadId: "thr_recovery",
          status: "suspended",
          threadRevision: 19,
        }}
        onResolveToolRecovery={resolveRecovery}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "跳过这一步" }));
    await waitFor(() => expect(resolveRecovery).toHaveBeenCalledTimes(1));
    rendered.rerender(
      <WorkProcess
        steps={[
          {
            ...recoveryToolStep("call_new_recovery"),
            threadId: "thr_new_recovery",
            turnId: "turn_new_recovery",
          },
        ]}
        turn={{
          turnId: "turn_new_recovery",
          threadId: "thr_new_recovery",
          status: "suspended",
          threadRevision: 31,
        }}
        onResolveToolRecovery={resolveRecovery}
      />,
    );

    expect(resolveRecovery).toHaveBeenCalledWith(
      expect.objectContaining({
        threadId: "thr_recovery",
        turnId: "turn_recovery",
        callId: "call_old_recovery",
        expectedThreadRevision: 19,
        expectedRecoveryRevision: 7,
        decision: "skip",
      }),
    );
    expect(resolveRecovery).toHaveBeenCalledTimes(1);
    release?.();
  });
});
