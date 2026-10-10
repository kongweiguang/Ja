// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  TimelineApproval,
  WorkStepAdapter,
} from "@/features/conversation/domain/timelineTypes";
import type { ConversationProcessPort } from "@/features/conversation/application/processSessionTypes";
import { WorkProcess } from "@/features/conversation/ui/timeline/WorkProcess";
import { TimelineDisclosureCache } from "@/features/conversation/ui/timeline/timelineDisclosure";

const turnId = "turn_tool_presentation";

/** 构造最小真实 Tool Item，让断言覆盖 WorkProcess 到 ToolStepDetails 的完整渲染链。 */
function toolStep(overrides: Partial<WorkStepAdapter> = {}): WorkStepAdapter {
  return {
    origin: "native" as const,
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

  /** 失败原因直接可见，用户仍可从唯一紧凑入口手动收起诊断。 */
  it("显示动作、真实 Tool 名称和首个目标并自动展示失败详情", () => {
    render(<WorkProcess steps={[toolStep()]} />);

    const trigger = screen.getByRole("button", { name: /调用工具，custom_mcp/u });
    expect(trigger).toBeVisible();
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(trigger).toHaveTextContent("调用工具");
    expect(trigger).toHaveTextContent('{"query":""}');
    expect(trigger.closest(".ja-tool-details")).toHaveAttribute("data-status", "error");
    expect(screen.getByText("query 不能为空")).toBeVisible();
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText("调用工具 .")).not.toBeInTheDocument();
  });

  /** 不把未校验参数当目标；无安全输入目标时用真实 MCP 名称指认失败项一次。 */
  it("未提供已验证目标的 MCP 失败行以真实工具名作为唯一目标", () => {
    const base = toolStep();
    const presentation = base.metadata?.presentation;
    if (presentation === undefined) throw new Error("test fixture presentation is missing");
    const unavailableToolName = "unknown_tool";
    const withoutMcpFallback: WorkStepAdapter = {
      ...base,
      itemId: "item_read_without_target",
      metadata: {
        ...base.metadata,
        callId: "call_read_without_target",
        toolName: "read",
        presentation: {
          ...presentation,
          kind: "read",
          inputPreview: undefined,
          outputPreview: "读取失败。",
        },
      },
    };
    const blankMcpInput: WorkStepAdapter = {
      ...base,
      itemId: "item_blank_mcp_input",
      metadata: {
        ...base.metadata,
        callId: "call_blank_mcp_input",
        toolName: "blank_input_tool",
        presentation: {
          ...presentation,
          inputPreview: " \t ",
          outputPreview: "参数预览为空白。",
        },
      },
    };
    render(
      <WorkProcess
        steps={[
          {
            ...base,
            metadata: {
              ...base.metadata,
              toolName: unavailableToolName,
              presentation: {
                ...presentation,
                inputPreview: undefined,
                outputPreview: "此工具当前不可用，未执行。",
              },
            },
          },
          blankMcpInput,
          withoutMcpFallback,
        ]}
      />,
    );

    const trigger = screen.getByRole("button", {
      name: "调用工具，unknown_tool，失败",
    });
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(trigger).toHaveTextContent("unknown_tool");
    expect(trigger.textContent?.match(/unknown_tool/gu)).toHaveLength(1);
    expect(screen.getByText("此工具当前不可用，未执行。")).toBeVisible();
    expect(screen.queryByText("等待参数校验")).not.toBeInTheDocument();
    expect(
      trigger.closest(".ja-tool-details")?.querySelector(".ja-tool-details__input"),
    ).toBeNull();

    const blankInputTrigger = screen.getByRole("button", {
      name: "调用工具，blank_input_tool，失败",
    });
    expect(
      blankInputTrigger.closest(".ja-tool-details")?.querySelector(".ja-tool-details__input"),
    ).toBeNull();

    const readTrigger = screen.getByRole("button", { name: "读取，read，失败" });
    expect(readTrigger).not.toHaveTextContent("read");
    expect(readTrigger).toHaveAttribute("aria-expanded", "true");
  });

  /** Shell 失败与其他 Tool 采用同一诊断策略，不额外增加错误入口。 */
  it("失败的 shell 命令自动展开诊断", () => {
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
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText(/command failed/u)).toBeVisible();
  });

  /** 已绑定持续会话时实时输出取代 ToolResult 正文，保持命令结果只出现一次。 */
  it("does not duplicate static shell output beside the live process output", async () => {
    const base = toolStep();
    const presentation = base.metadata?.presentation;
    if (presentation === undefined) throw new Error("test fixture presentation is missing");
    const port: ConversationProcessPort = {
      list: vi.fn().mockResolvedValue({
        generation: 4,
        sessions: [
          {
            workspaceId: "ws_project",
            threadId: base.threadId,
            generation: 3,
            sessionId: "proc_0123456789abcdef0123456789abcdef",
            startedTurnId: base.turnId,
            startedCallId: "call_custom_mcp",
            state: "running",
            outputSequence: 5,
            outputTruncated: false,
            exitCode: null,
          },
        ],
      }),
      read: vi.fn().mockResolvedValue({
        workspaceId: "ws_project",
        threadId: base.threadId,
        generation: 3,
        sessionId: "proc_0123456789abcdef0123456789abcdef",
        startedTurnId: base.turnId,
        startedCallId: "call_custom_mcp",
        state: "running",
        outputSequence: 5,
        outputTruncated: false,
        exitCode: null,
        output: "实时命令输出",
        nextSequence: 5,
      }),
      write: vi.fn().mockImplementation(async (input) => ({
        workspaceId: input.workspaceId,
        threadId: input.threadId,
        generation: 3,
        sessionId: "proc_0123456789abcdef0123456789abcdef",
        startedTurnId: base.turnId,
        startedCallId: "call_custom_mcp",
        state: "running" as const,
        outputSequence: 5,
        outputTruncated: false,
        exitCode: null,
        output: "实时命令输出",
        nextSequence: 5,
      })),
      stop: vi.fn().mockImplementation(async (input) => ({
        workspaceId: input.workspaceId,
        threadId: input.threadId,
        generation: 3,
        sessionId: "proc_0123456789abcdef0123456789abcdef",
        startedTurnId: base.turnId,
        startedCallId: "call_custom_mcp",
        state: "exited" as const,
        outputSequence: 5,
        outputTruncated: false,
        exitCode: 0,
        output: "实时命令输出",
        nextSequence: 5,
      })),
    };
    const { container } = render(
      <WorkProcess
        steps={[
          {
            ...base,
            status: "completed",
            metadata: {
              ...base.metadata,
              toolName: "exec_command",
              presentation: {
                ...presentation,
                kind: "shell",
                status: "success",
                title: "执行命令",
                command: "pnpm build",
                outputPreview: "静态命令输出",
                stdout: "静态命令输出",
              },
            },
          },
        ]}
        processSession={{ workspaceId: "ws_project", generation: 4, port }}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /查看工作过程/u }));
    fireEvent.click(screen.getByRole("button", { name: /执行命令，exec_command，pnpm build/u }));
    expect(await screen.findByText("实时命令输出")).toBeVisible();
    expect(container.querySelectorAll(".ja-tool-details__output")).toHaveLength(0);
    expect(container.querySelectorAll(".ja-process-session__output")).toHaveLength(1);
  });

  /** 已完成 Turn 的非零 shell 退出码只改变诊断可见性，不改写 Tool/Turn 的权威成功状态。 */
  it("opens archived process details for a nonzero shell exit", async () => {
    const base = toolStep();
    const presentation = base.metadata?.presentation;
    if (presentation === undefined) throw new Error("test fixture presentation is missing");
    render(
      <WorkProcess
        displayMode="archived"
        steps={[
          {
            ...base,
            status: "completed",
            metadata: {
              ...base.metadata,
              toolName: "exec_command",
              presentation: {
                ...presentation,
                kind: "shell",
                status: "success",
                title: "执行命令",
                command: "pnpm build",
                stdout: "build failed",
                exitCode: 2,
              },
            },
          },
        ]}
      />,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent("退出码 2");
    expect(screen.getByRole("button", { name: /收起工作过程/u })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
  });

  /** 被 ApprovalCard 承载的 approval Item 隐去重复标题/状态，安全命令直接留在 Tool 行。 */
  it("renders one actionable approval card beside the presented Tool row", () => {
    const base = toolStep();
    const presentation = base.metadata?.presentation;
    if (presentation === undefined) throw new Error("test fixture presentation is missing");
    const approvalId = "appr_presented_command";
    const approval: TimelineApproval = {
      approvalId,
      threadId: base.threadId,
      turnId: base.turnId,
      threadRevision: 2,
      callId: "call_custom_mcp",
      toolName: "exec_command",
      reason: "执行外部命令",
      expiresAt: "2099-08-17T12:00:00Z",
    };
    const tool = {
      ...base,
      status: "in_progress" as const,
      metadata: {
        ...base.metadata,
        toolName: "exec_command",
        presentation: {
          ...presentation,
          kind: "shell" as const,
          status: "waiting_approval" as const,
          title: "执行命令",
          command: "Write-Output 'safe command'",
        },
      },
    };
    const approvalEvent: WorkStepAdapter = {
      ...base,
      itemId: "item_approval_event",
      kind: "approval",
      status: "in_progress",
      title: "确认 exec_command",
      metadata: { approvalId, callId: approval.callId, toolName: approval.toolName },
    };
    render(<WorkProcess steps={[tool, approvalEvent]} approvals={[approval]} />);

    expect(screen.getAllByText("执行命令")).toHaveLength(2);
    expect(screen.getAllByText("Write-Output 'safe command'")).toHaveLength(2);
    expect(screen.getByRole("button", { name: "批准" })).toBeVisible();
    expect(screen.getByRole("button", { name: "拒绝" })).toBeVisible();
    expect(screen.queryByText("确认 exec_command")).not.toBeInTheDocument();
    expect(screen.queryByText("进行中", { exact: true })).not.toBeInTheDocument();
    expect(screen.queryByText(approval.callId)).not.toBeInTheDocument();
  });

  /** continuation 会把多个 Turn 合并进一个过程区；复用 callId 时审批仍按各自 Turn 排序。 */
  it("隔离跨 Turn 重复 callId 的审批排序", () => {
    const base = toolStep();
    const firstTurn = {
      ...base,
      itemId: "item_first_turn",
      turnId: "turn_first",
      metadata: { ...base.metadata, callId: "call_reused" },
    };
    const secondTurn = {
      ...base,
      itemId: "item_second_turn",
      turnId: "turn_second",
      metadata: { ...base.metadata, callId: "call_reused" },
    };
    /** Keep each fixture tied to its owning Turn when the Provider reuses a call ID. */
    const approval = (approvalId: string, turnId: string, reason: string): TimelineApproval => ({
      approvalId,
      threadId: "thr_tool_presentation",
      turnId,
      threadRevision: 1,
      callId: "call_reused",
      toolName: "custom_mcp",
      reason,
      expiresAt: "2099-08-17T12:00:00Z",
    });

    render(
      <WorkProcess
        steps={[firstTurn, secondTurn]}
        approvals={[
          approval("appr_second_turn", "turn_second", "第二轮审批"),
          approval("appr_first_turn", "turn_first", "第一轮审批"),
        ]}
      />,
    );

    const cards = Array.from(document.querySelectorAll(".ja-approval-card"));
    expect(cards.map((card) => card.textContent)).toEqual([
      expect.stringContaining("第一轮审批"),
      expect.stringContaining("第二轮审批"),
    ]);
  });

  /** 正常详情只保留实际结果，协议中的状态、耗时和截断事实不应成为重复视觉噪声。 */
  it("展开正常工具时不重复状态、耗时、根目录或成功退出码", () => {
    const base = toolStep();
    const presentation = base.metadata?.presentation;
    if (presentation === undefined) throw new Error("test fixture presentation is missing");
    render(
      <WorkProcess
        steps={[
          {
            ...base,
            status: "completed",
            metadata: {
              ...base.metadata,
              toolName: "shell",
              presentation: {
                ...presentation,
                kind: "shell",
                status: "success",
                command: "pnpm test",
                relativeCwd: ".",
                stdout: "验证结果",
                exitCode: 0,
                durationMs: 1_250,
                truncated: true,
              },
            },
          },
        ]}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /查看工作过程，已完成/u }));
    fireEvent.click(screen.getByRole("button", { name: /执行命令，shell，pnpm test，完成/u }));
    expect(screen.getByText(/验证结果/u)).toBeVisible();
    expect(screen.queryByText("状态：完成")).not.toBeInTheDocument();
    expect(screen.queryByText("1.3 s")).not.toBeInTheDocument();
    expect(screen.queryByText("工作目录：.")).not.toBeInTheDocument();
    expect(screen.queryByText("退出码 0")).not.toBeInTheDocument();
    expect(screen.queryByText("预览已截断")).not.toBeInTheDocument();
  });

  /** 自动展开只提供默认值；历史重挂载不能推翻用户已收起的失败详情。 */
  it("跨卸载保留用户手动收起的失败诊断", () => {
    const cache = new TimelineDisclosureCache();
    const props = {
      steps: [toolStep()],
      disclosureCache: cache,
      disclosureKey: "exchange:failed-tool",
      disclosureThreadId: "thr_tool_presentation",
    };
    const first = render(<WorkProcess {...props} />);
    const trigger = screen.getByRole("button", { name: /调用工具，custom_mcp/u });
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    fireEvent.click(trigger);
    first.unmount();

    render(<WorkProcess {...props} />);
    expect(screen.getByRole("button", { name: /调用工具，custom_mcp/u })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
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
            status: "completed",
            metadata: {
              ...step.metadata,
              toolName: "grep",
              presentation: {
                ...presentation,
                kind: "read",
                status: "success",
                summary: "找到 2 个匹配项",
                outputPreview:
                  "src/App.tsx:12: const needle = true;\nsrc/App.tsx-13- export default App;",
              },
            },
          },
        ]}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /查看工作过程/u }));
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
          origin: "native",
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
        operationKey: expect.stringMatching(/^recovery-[0-9a-f]{8}$/u),
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
          origin: "native",
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
          origin: "native",
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
