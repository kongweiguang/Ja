// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  TimelineApproval as ApprovalSummary,
  TimelineItemAdapter,
} from "@/features/conversation/domain/timelineTypes";
import { ChatTimeline } from "@/features/conversation/ui/timeline/ChatTimeline";
import { MarkdownMessage } from "@/features/conversation/ui/timeline/MarkdownMessage";
import { WorkProcess } from "@/features/conversation/ui/timeline/WorkProcess";
import { TurnChangesCard } from "@/features/conversation/ui/timeline/TurnChangesCard";

const turnId = "turn_one";
/** 构建一个 Renderer Item，同时让每个 UI 断言只关注自己持有的字段。 */
const baseItem = (item: Partial<TimelineItemAdapter>): TimelineItemAdapter => ({
  itemId: "item_one",
  threadId: "thr_one",
  turnId,
  kind: "agent_message",
  status: "completed",
  ...item,
});

describe("ChatTimeline", () => {
  afterEach(() => cleanup());

  it("renders one user question and one final answer without identity avatars", () => {
    render(
      <ChatTimeline
        items={[
          baseItem({ itemId: "item_user", kind: "user_message", text: "检查代码" }),
          baseItem({ itemId: "item_agent", text: "处理中 **阶段**" }),
          baseItem({ itemId: "item_final", final: true, text: "完成 <script>alert(1)</script>" }),
        ]}
      />,
    );
    expect(screen.getByRole("article", { name: "用户问题" })).toBeVisible();
    expect(document.querySelector(".ja-chat-message__avatar")).toBeNull();
    expect(screen.queryByText("你")).not.toBeInTheDocument();
    expect(screen.queryByText("Agent")).not.toBeInTheDocument();
    expect(screen.getByRole("article", { name: "最终答复" })).toBeVisible();
    expect(document.querySelectorAll('[data-role="final"]')).toHaveLength(1);
    expect(
      screen.getAllByText((_, element) => element?.textContent === "处理中 阶段").length,
    ).toBeGreaterThan(0);
    // rehype-sanitize 会移除可执行 Markup；其惰性文本允许继续可见，避免静默改写 Assistant Response。
    expect(document.querySelector("script")).toBeNull();
  });

  it("keeps a failed work process open but folds after completion", async () => {
    const user = userEvent.setup();
    const first = baseItem({
      itemId: "item_command",
      kind: "command",
      title: "运行测试",
      text: "C:\\secret\\command --token hidden",
      summary: "命令已提交",
      status: "in_progress",
    });
    const { rerender } = render(<WorkProcess steps={[first]} />);
    expect(screen.getByText("命令已提交")).toBeVisible();
    expect(screen.queryByText(/secret|token|hidden/iu)).not.toBeInTheDocument();
    const completed = { ...first, status: "completed" as const };
    rerender(<WorkProcess steps={[completed]} />);
    expect(screen.getByRole("button", { name: /工作过程/ })).toHaveAttribute(
      "data-state",
      "closed",
    );
    await user.click(screen.getByRole("button", { name: /工作过程/ }));
    expect(screen.getByText("命令已提交")).toBeVisible();

    rerender(<WorkProcess steps={[{ ...completed, status: "failed" as const }]} />);
    expect(screen.getByRole("button", { name: /失败/ })).toHaveAttribute("data-state", "open");
  });

  /** Turn 是整轮状态 owner，覆盖六态并锁定局部失败不能抢占运行态。 */
  it("完整映射六种权威 Turn 状态且始终使用稳定标题", () => {
    const step = baseItem({
      itemId: "item_state",
      kind: "commentary",
      title: "状态检查",
      status: "completed",
    });
    const cases = [
      { status: "queued" as const, state: "queued", label: "排队中" },
      { status: "running" as const, state: "active", label: "进行中…" },
      { status: "waiting_approval" as const, state: "waiting", label: "等待确认" },
      { status: "completed" as const, state: "completed", label: "已完成" },
      { status: "failed" as const, state: "failed", label: "失败" },
      { status: "cancelled" as const, state: "cancelled", label: "已取消" },
    ] as const;
    const { rerender } = render(
      <WorkProcess
        steps={[step]}
        turn={{ turnId, threadId: "thr_one", status: cases[0].status }}
      />,
    );

    cases.forEach((entry, index) => {
      if (index > 0) {
        rerender(
          <WorkProcess
            steps={[step]}
            turn={{ turnId, threadId: "thr_one", status: entry.status }}
          />,
        );
      }
      const region = screen.getByRole("region", { name: "工作过程" });
      const trigger = region.querySelector("button");
      expect(region).toHaveAttribute("data-state", entry.state);
      expect(trigger).not.toBeNull();
      expect(trigger).toHaveAccessibleName(new RegExp(entry.label, "u"));
      expect(trigger?.querySelector(".ja-work-process__heading")).toHaveTextContent("工作过程");
      expect(trigger?.querySelector(".ja-work-process__summary")).toHaveTextContent(entry.label);
      expect(trigger?.querySelectorAll(".ja-work-process__summary")).toHaveLength(1);
      expect(trigger).not.toHaveTextContent("工作中");
      expect(trigger).not.toHaveTextContent("生成中");
    });

    rerender(
      <WorkProcess
        steps={[{ ...step, status: "failed" }]}
        turn={{ turnId, threadId: "thr_one", status: "running" }}
      />,
    );
    const runningWithLocalFailure = screen.getByRole("button", { name: /进行中/u });
    expect(runningWithLocalFailure).toHaveAttribute("data-state", "open");
    expect(runningWithLocalFailure.querySelector(".ja-work-process__summary")).toHaveTextContent(
      "进行中…",
    );
    expect(runningWithLocalFailure.querySelector(".ja-work-process__failure")).toBeNull();
  });

  /** 无明细状态行不伪造可操作性，避免用户打开一个没有内容的 Disclosure。 */
  it("无明细时只显示状态，不提供空折叠操作或零步占位", () => {
    render(<WorkProcess steps={[]} turn={{ turnId, threadId: "thr_one", status: "queued" }} />);

    const region = screen.getByRole("region", { name: "工作过程" });
    expect(region.querySelector("button")).toBeNull();
    expect(screen.getByRole("status", { name: "工作过程，排队中" })).toBeVisible();
    expect(region.querySelector(".ja-work-process__chevron")).toBeNull();
    expect(region.querySelector(".ja-work-process__step-count")).toBeNull();
    expect(region).not.toHaveTextContent("0 步");
  });

  // DOM 归属断言锁定单行摘要结构，防止状态图标和数字徽标重新挤回标题左侧。
  it("uses a compact completed summary while preserving a recovered failed step", () => {
    const failedStep = baseItem({
      itemId: "item_failed_read",
      kind: "tool_call",
      title: "读取缺失文件",
      summary: "读取失败后已继续执行",
      status: "failed",
    });

    const { rerender } = render(
      <WorkProcess
        steps={[failedStep]}
        turn={{
          turnId,
          threadId: "thr_one",
          status: "completed",
          startedAt: "2026-08-30T12:00:00.000Z",
          completedAt: "2026-08-30T12:00:01.000Z",
        }}
      />,
    );

    const trigger = screen.getByRole("button", { name: /已完成.*1 步失败/u });
    expect(trigger).toHaveAttribute("data-state", "closed");
    expect(trigger.querySelector(".ja-work-process__status-icon")).toBeNull();
    expect(trigger.querySelector(".ja-work-process__count")).toBeNull();
    expect(trigger.querySelector(".lucide-clock-3")).toBeNull();
    expect(trigger.querySelectorAll("svg")).toHaveLength(1);
    expect(trigger.querySelector(".ja-work-process__heading")).toHaveTextContent("工作过程");
    expect(trigger.querySelector(".ja-work-process__summary")).toHaveTextContent("已完成");
    expect(trigger.querySelector(".ja-work-process__failure")).toHaveTextContent("1 步失败");
    expect(trigger.querySelector(".ja-work-process__duration")).toHaveTextContent("1秒");
    expect(trigger.querySelector(".ja-work-process__step-count")).toHaveTextContent("1 步");
    expect(trigger).toHaveTextContent(/已完成\s*·\s*1 步失败\s*·\s*1秒\s*·\s*1 步/u);
    expect(screen.queryByText(/总耗时|^用时/u)).not.toBeInTheDocument();
    expect(
      trigger.querySelector(".ja-work-process__meta .ja-work-process__summary"),
    ).not.toBeNull();
    expect(screen.getByRole("region", { name: "工作过程" })).toHaveAttribute(
      "data-state",
      "completed",
    );

    rerender(
      <WorkProcess
        steps={[{ ...failedStep, status: "completed" }]}
        turn={{
          turnId,
          threadId: "thr_one",
          status: "completed",
          startedAt: "2026-08-30T12:00:00.000Z",
          completedAt: "2026-08-30T12:00:01.000Z",
        }}
      />,
    );
    expect(
      screen.getByRole("button", { name: /工作过程/ }).querySelector(".ja-work-process__summary"),
    ).toHaveTextContent("已完成");
    expect(screen.queryByText(/个步骤/u)).not.toBeInTheDocument();
    expect(screen.queryByText(/步骤已完成/u)).not.toBeInTheDocument();
  });

  /** 中文耗时按阅读价值逐级降精度，且存在 Turn 时绝不拿步骤耗时伪造整轮耗时。 */
  it("以紧凑中文格式展示权威耗时，并仅在无 Turn 时回退步骤合计", () => {
    const step = baseItem({
      itemId: "item_duration",
      kind: "commentary",
      title: "耗时检查",
      status: "completed",
      durationMs: 7_300,
    });
    const completedTurn = {
      turnId,
      threadId: "thr_one",
      status: "completed" as const,
      startedAt: "2026-08-30T12:00:00.000Z",
      completedAt: "2026-08-30T12:15:11.000Z",
    };
    const { rerender } = render(<WorkProcess steps={[step]} turn={completedTurn} />);

    expect(screen.getByText("15分11秒")).toBeVisible();
    expect(screen.queryByText("7.3秒")).not.toBeInTheDocument();

    rerender(
      <WorkProcess steps={[step]} turn={{ turnId, threadId: "thr_one", status: "completed" }} />,
    );
    expect(document.querySelector(".ja-work-process__duration")).toBeNull();

    rerender(<WorkProcess steps={[step]} />);
    expect(screen.getByText("7.3秒")).toBeVisible();

    rerender(
      <WorkProcess
        steps={[step]}
        turn={{
          ...completedTurn,
          completedAt: "2026-08-30T13:15:11.000Z",
        }}
      />,
    );
    expect(screen.getByText("1小时15分")).toBeVisible();
  });

  it("keeps protocol phase names out of the visible work process", () => {
    render(
      <WorkProcess
        steps={[
          baseItem({
            itemId: "item_progress",
            kind: "commentary",
            title: "回复过程",
            text: "正在分析公开上下文",
            status: "in_progress",
            metadata: { phase: "assistant_progress", modelRound: 2 },
          }),
        ]}
      />,
    );

    expect(screen.getByText("模型第 2 轮")).toBeVisible();
    expect(screen.queryByText("assistant_progress")).not.toBeInTheDocument();
    expect(screen.queryByText("reasoning_summary")).not.toBeInTheDocument();
  });

  it("gives a replacement approval a fresh identity and explicit action context", async () => {
    const approvalA: ApprovalSummary = {
      approvalId: "appr_one",
      threadId: "thr_one",
      turnId,
      threadRevision: 1,
      callId: "call_one",
      toolName: "shell",
      reason: "运行测试",
      expiresAt: "2099-08-17T12:00:00+08:00",
    };
    const approvalB: ApprovalSummary = {
      ...approvalA,
      approvalId: "appr_two",
      callId: "call_two",
      toolName: "read",
      reason: "读取工作区文件",
    };
    const step = baseItem({
      itemId: "item_command",
      kind: "command",
      title: "运行命令",
      status: "in_progress",
      metadata: { callId: "call_one", toolName: "shell" },
    });
    const { rerender } = render(
      <WorkProcess
        steps={[step]}
        approvals={[approvalA]}
        approvalDecisions={{ [approvalA.approvalId]: "approve" }}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("已批准");
    rerender(<WorkProcess steps={[step]} approvals={[approvalB]} onApprovalDecision={vi.fn()} />);

    await waitFor(() => {
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "批准" })).toBeEnabled();
    });
    expect(
      screen.getByRole("button", { name: /工作过程/ }).querySelector(".ja-work-process__summary"),
    ).toHaveTextContent("等待确认");
    expect(screen.getByText("读取工作区文件")).toBeVisible();
    expect(screen.getByText("read")).toBeVisible();
    expect(screen.getByText("call_two")).toBeVisible();
  });

  it("coalesces adjacent tool items into one work process", () => {
    render(
      <ChatTimeline
        items={[
          baseItem({
            itemId: "item_read",
            kind: "tool_call",
            title: "读取文件",
            text: "src/App.tsx",
          }),
          baseItem({
            itemId: "item_patch",
            kind: "command",
            title: "应用补丁",
            text: "补丁已应用",
          }),
          baseItem({ itemId: "item_answer", text: "完成" }),
        ]}
      />,
    );
    expect(screen.getAllByRole("region", { name: "工作过程" })).toHaveLength(1);
    expect(screen.getAllByText("完成").length).toBeGreaterThan(0);
  });

  it("keeps persisted attachment metadata visible when the completed work process is folded", () => {
    render(
      <ChatTimeline
        items={[
          baseItem({
            itemId: "item_attachment",
            kind: "commentary",
            title: "设计稿.pdf",
            summary: "PDF · 2.0 KB",
            metadata: {
              attachmentId: "att_snapshot",
              sizeBytes: 2048,
              mediaKind: "pdf",
              mediaType: "application/pdf",
              attachmentState: "bound",
            },
          }),
          baseItem({ itemId: "item_user", kind: "user_message", text: "检查附件" }),
          baseItem({ itemId: "item_answer", text: "已检查" }),
        ]}
      />,
    );

    expect(screen.queryByRole("button", { name: /工作过程/ })).not.toBeInTheDocument();
    expect(screen.getByRole("list", { name: "附件" })).toBeVisible();
    expect(screen.getByText("设计稿.pdf")).toBeVisible();
    expect(screen.getByText("PDF · 2.0 KB")).toBeVisible();
  });

  it("shows turn duration, explicit terminal failure, and file diff stats", () => {
    render(
      <ChatTimeline
        turns={[
          {
            turnId,
            threadId: "thr_one",
            status: "failed",
            startedAt: "2099-08-17T12:00:00+08:00",
            completedAt: "2099-08-17T12:00:01.250+08:00",
            error: { code: "TURN_FAILED", retryable: true },
          },
        ]}
        items={[
          baseItem({
            itemId: "item_command",
            kind: "command",
            title: "应用补丁",
            metadata: {
              changedFiles: 2,
              additions: 7,
              deletions: 3,
              relativePaths: ["src/Main.tsx", "docs/readme.md"],
              truncated: false,
            },
            durationMs: 200,
            status: "failed",
          }),
        ]}
      />,
    );
    expect(screen.getByRole("region", { name: "工作过程" })).toHaveAttribute(
      "data-state",
      "failed",
    );
    expect(screen.getByText("1.3秒")).toBeVisible();
    expect(screen.getAllByText("2 个文件").length).toBeGreaterThan(0);
    expect(screen.getByText("src/Main.tsx")).toBeVisible();
    expect(screen.getByText("docs/readme.md")).toBeVisible();
    expect(screen.getAllByText("+7").length).toBeGreaterThan(0);
    expect(screen.getAllByText("−3").length).toBeGreaterThan(0);
    expect(screen.getByRole("alert")).toHaveTextContent("TURN_FAILED");
  });

  it("工作过程成功摘要不夹带文件变更、同步状态或零修改占位", () => {
    const step = baseItem({
      itemId: "item_summary",
      kind: "commentary",
      title: "完成检查",
      status: "completed",
    });
    const availableTurn = {
      turnId,
      threadId: "thr_one",
      status: "completed" as const,
      startedAt: "2099-08-17T12:00:00Z",
      completedAt: "2099-08-17T12:00:01Z",
      changeSet: {
        state: "available" as const,
        files: [],
        stats: { files: 2, additions: 7, deletions: 3, binaryFiles: 0, truncated: false },
      },
    };
    const { rerender } = render(<WorkProcess steps={[step]} turn={availableTurn} />);
    expect(
      screen
        .getByRole("button", { name: /工作过程/ })
        .querySelector(".ja-work-process__step-count"),
    ).toHaveTextContent("1 步");
    expect(screen.queryByText(/2 个文件|\+7|−3/u)).not.toBeInTheDocument();

    rerender(
      <WorkProcess
        steps={[step]}
        turn={{
          ...availableTurn,
          changeSet: {
            ...availableTurn.changeSet,
            stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
          },
        }}
      />,
    );
    expect(screen.queryByText(/0 个文件|无修改/u)).not.toBeInTheDocument();

    rerender(
      <WorkProcess
        steps={[step]}
        turn={{
          ...availableTurn,
          changeSet: {
            state: "unavailable",
            reason: "capture_failed",
            files: [],
            stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
          },
        }}
      />,
    );
    expect(screen.queryByText(/文件差异捕获失败/u)).not.toBeInTheDocument();

    rerender(<WorkProcess steps={[step]} turn={{ ...availableTurn, changeSet: undefined }} />);
    expect(screen.queryByText(/正在同步修改记录/u)).not.toBeInTheDocument();

    rerender(<WorkProcess steps={[step]} turn={{ ...availableTurn, changeSet: null }} />);
    expect(screen.queryByText(/未记录本轮文件差异/u)).not.toBeInTheDocument();
  });

  it("按 Tool 类型区分读取、修改、命令路径与资源路径", () => {
    const readStep = baseItem({
      itemId: "item_read_path",
      kind: "tool_call",
      status: "in_progress",
      metadata: {
        presentation: {
          kind: "read",
          title: "读取文件",
          status: "running",
          relativePaths: ["src/read.ts"],
          truncated: false,
        },
      },
    });
    const { rerender } = render(<WorkProcess steps={[readStep]} />);
    expect(screen.getByRole("list", { name: "读取的文件" })).toHaveTextContent("src/read.ts");
    expect(screen.queryByRole("list", { name: "修改的文件" })).not.toBeInTheDocument();

    rerender(
      <WorkProcess
        steps={[
          {
            ...readStep,
            itemId: "item_edit_path",
            metadata: {
              presentation: {
                kind: "edit",
                title: "编辑文件",
                status: "running",
                relativePaths: ["src/edit.ts"],
                truncated: false,
              },
            },
          },
        ]}
      />,
    );
    expect(screen.getByRole("list", { name: "修改的文件" })).toHaveTextContent("src/edit.ts");

    rerender(
      <WorkProcess
        steps={[
          {
            ...readStep,
            itemId: "item_shell_path",
            metadata: {
              presentation: {
                kind: "shell",
                title: "运行命令",
                status: "running",
                relativePaths: ["package.json"],
                truncated: false,
              },
            },
          },
        ]}
      />,
    );
    expect(screen.getByRole("list", { name: "命令涉及的文件" })).toHaveTextContent("package.json");
  });

  it("展示 Shell 的命令、分流输出、退出事实与十行预览", async () => {
    const user = userEvent.setup();
    const stdout = Array.from({ length: 12 }, (_, index) => `输出 ${index + 1}`).join("\n");
    render(
      <WorkProcess
        steps={[
          baseItem({
            itemId: "item_shell",
            kind: "tool_call",
            title: "运行命令",
            metadata: {
              callId: "call_shell",
              toolName: "shell",
              presentation: {
                kind: "shell",
                title: "运行命令",
                status: "error",
                relativePaths: [],
                command: "pnpm test",
                relativeCwd: "workspace",
                stdout,
                stderr: "测试失败",
                exitCode: 1,
                durationMs: 1_250,
                truncated: false,
              },
            },
            status: "failed",
          }),
        ]}
      />,
    );

    const output = document.querySelector(".ja-tool-details__output");
    expect(screen.getByText("pnpm test")).toBeVisible();
    expect(screen.getByText("工作目录：workspace")).toBeVisible();
    expect(output).toHaveTextContent("stdout");
    expect(output).toHaveTextContent("输出 9");
    expect(output).not.toHaveTextContent("输出 10");
    expect(screen.getByText("退出码 1")).toBeVisible();
    expect(screen.getByText("状态：失败")).toBeVisible();
    expect(document.querySelector(".ja-tool-details__facts")).toHaveTextContent("1.3 s");
    await user.click(screen.getByRole("button", { name: /展开全部/ }));
    expect(output).toHaveTextContent("输出 12");
    expect(output).toHaveTextContent("stderr");
    expect(output).toHaveTextContent("测试失败");
  });

  it("只通过四重身份加载完整 Tool artifact，并提供稳定失败反馈", async () => {
    const user = userEvent.setup();
    const readArtifact = vi.fn(async () => "完整第一行\n完整第二行");
    const step = baseItem({
      itemId: "item_artifact",
      kind: "tool_call",
      title: "读取文件",
      metadata: {
        callId: "call_artifact",
        toolName: "read",
        presentation: {
          kind: "read",
          title: "读取文件",
          status: "success",
          outputPreview: "安全预览",
          relativePaths: ["src/main.ts"],
          truncated: true,
          artifactId: "artifact_tool",
        },
      },
    });
    const { unmount } = render(<WorkProcess steps={[step]} onReadToolArtifact={readArtifact} />);
    await user.click(screen.getByRole("button", { name: /工作过程/ }));
    await user.click(screen.getByRole("button", { name: "加载完整输出" }));
    await waitFor(() => expect(screen.getByText(/完整第一行/)).toBeVisible());
    expect(readArtifact).toHaveBeenCalledWith({
      threadId: "thr_one",
      turnId,
      callId: "call_artifact",
      artifactId: "artifact_tool",
    });

    unmount();
    render(
      <WorkProcess
        steps={[{ ...step, itemId: "item_artifact_failed" }]}
        onReadToolArtifact={async () => Promise.reject(new Error("native secret"))}
      />,
    );
    await user.click(screen.getByRole("button", { name: /工作过程/ }));
    await user.click(screen.getByRole("button", { name: "加载完整输出" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("无法读取完整输出");
    expect(screen.queryByText(/native secret/)).not.toBeInTheDocument();
  });

  it("展示冻结修改事实，且仅在 reader 可用时提供审查入口", async () => {
    const user = userEvent.setup();
    const turn = { turnId, threadId: "thr_one", status: "completed" as const };
    const available = {
      state: "available" as const,
      files: [
        {
          path: "src/new.ts",
          oldPath: "src/old.ts",
          status: "renamed" as const,
          additions: 4,
          deletions: 2,
          binary: false,
          truncated: false,
        },
        {
          path: "assets/logo.png",
          status: "modified" as const,
          binary: true,
          truncated: false,
        },
      ],
      stats: { files: 2, additions: 4, deletions: 2, binaryFiles: 1, truncated: false },
      artifactId: "artifact_diff",
    };
    const readDiff = vi.fn(async () => "--- a/src/old.ts\n+++ b/src/new.ts\n+changed");
    const { rerender } = render(
      <TurnChangesCard turn={turn} changeSet={available} onReadDiff={readDiff} />,
    );
    expect(screen.getByText("2 个文件发生修改")).toBeVisible();
    expect(screen.getByText("src/old.ts → src/new.ts")).toBeVisible();
    expect(screen.getByText("二进制")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "审查修改" }));
    expect(await screen.findByLabelText("Unified diff")).toHaveTextContent("+++ b/src/new.ts");
    expect(readDiff).toHaveBeenCalledWith({
      threadId: "thr_one",
      turnId,
      artifactId: "artifact_diff",
    });
    await user.click(screen.getByRole("button", { name: "关闭修改审查" }));

    rerender(<TurnChangesCard turn={turn} changeSet={{ ...available, artifactId: undefined }} />);
    expect(screen.queryByRole("button", { name: "审查修改" })).not.toBeInTheDocument();
    rerender(
      <TurnChangesCard
        turn={turn}
        changeSet={{
          state: "available",
          files: [],
          stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
        }}
      />,
    );
    expect(screen.queryByRole("region", { name: "修改记录" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "审查修改" })).not.toBeInTheDocument();
  });

  it("流式答复原位显示，并固定工作过程、答复与可靠非零变更的顺序", () => {
    const runningTurn = {
      turnId,
      threadId: "thr_one",
      status: "running" as const,
    };
    const completedTurn = {
      ...runningTurn,
      status: "completed" as const,
      changeSet: {
        state: "available" as const,
        files: [
          {
            path: "src/stream.ts",
            status: "modified" as const,
            additions: 2,
            deletions: 1,
            binary: false,
            truncated: false,
          },
        ],
        stats: { files: 1, additions: 2, deletions: 1, binaryFiles: 0, truncated: false },
      },
    };
    const { rerender } = render(
      <ChatTimeline
        turns={[runningTurn]}
        items={[
          baseItem({
            itemId: "draft:turn_one",
            status: "in_progress",
            text: "正在流式生成",
          }),
        ]}
      />,
    );

    const streamingAnswer = screen.getByRole("article", { name: "最终答复" });
    expect(streamingAnswer).toHaveAttribute("aria-busy", "true");
    expect(streamingAnswer).toHaveTextContent("正在流式生成");

    rerender(
      <ChatTimeline
        turns={[completedTurn]}
        items={[
          baseItem({
            itemId: "item_work",
            kind: "commentary",
            title: "完成检查",
            status: "completed",
          }),
          baseItem({ itemId: "item_final", final: true, text: "最终结果" }),
        ]}
      />,
    );
    const process = screen.getByRole("region", { name: "工作过程" });
    const finalAnswer = screen.getByRole("article", { name: "最终答复" });
    const changes = screen.getByRole("region", { name: "修改记录" });
    expect(finalAnswer).toHaveTextContent("最终结果");
    expect(
      process.compareDocumentPosition(finalAnswer) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(
      finalAnswer.compareDocumentPosition(changes) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);

    rerender(
      <ChatTimeline
        turns={[
          {
            ...completedTurn,
            status: "failed",
            error: { code: "TURN_FAILED", retryable: true },
          },
        ]}
        items={[
          baseItem({ itemId: "item_failed", status: "failed", final: true, text: "失败结果" }),
        ]}
      />,
    );
    const error = screen.getByRole("alert");
    const failedChanges = screen.getByRole("region", { name: "修改记录" });
    expect(
      error.compareDocumentPosition(failedChanges) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);

    rerender(
      <ChatTimeline
        turns={[
          {
            ...completedTurn,
            changeSet: {
              state: "available",
              files: [],
              stats: {
                files: 0,
                additions: 0,
                deletions: 0,
                binaryFiles: 0,
                truncated: false,
              },
            },
          },
        ]}
        items={[baseItem({ itemId: "item_zero", final: true, text: "无修改结果" })]}
      />,
    );
    expect(screen.queryByRole("region", { name: "修改记录" })).not.toBeInTheDocument();

    rerender(
      <ChatTimeline
        turns={[
          {
            ...completedTurn,
            changeSet: {
              state: "unavailable",
              reason: "not_git",
              files: [],
              stats: {
                files: 0,
                additions: 0,
                deletions: 0,
                binaryFiles: 0,
                truncated: false,
              },
            },
          },
        ]}
        items={[baseItem({ itemId: "item_unavailable", final: true, text: "不可用结果" })]}
      />,
    );
    expect(screen.queryByRole("region", { name: "修改记录" })).not.toBeInTheDocument();
  });

  it("keeps long history bounded and preserves a user's scrolled position", async () => {
    const manyItems = Array.from({ length: 220 }, (_, index) =>
      baseItem({ itemId: `item_${index}`, text: `消息 ${index}` }),
    );
    const { container, rerender } = render(<ChatTimeline items={manyItems} />);
    const scroll = container.querySelector(".ja-chat-timeline__scroll");
    expect(scroll).not.toBeNull();
    if (scroll === null) {
      return;
    }
    expect(scroll.querySelectorAll(".ja-chat-timeline__row").length).toBeLessThan(100);
    Object.defineProperties(scroll, {
      clientHeight: { configurable: true, value: 400 },
      scrollHeight: { configurable: true, value: 4_000 },
      scrollTop: { configurable: true, writable: true, value: 100 },
    });
    fireEvent.scroll(scroll);
    rerender(
      <ChatTimeline
        items={manyItems.map((item, index) =>
          index === 219 ? { ...item, text: "最后一条更新" } : item,
        )}
      />,
    );
    await waitFor(() => expect(scroll).toHaveProperty("scrollTop", 100));
  });

  /** 只有固定在底部时 Stream Row 才能移动 Viewport；实际 Offset 仍由 Virtualizer 负责。 */
  it("follows streamed text in the final row while the user is already at the bottom", async () => {
    const items = [
      baseItem({ itemId: "item_1", text: "第一段" }),
      baseItem({ itemId: "item_2", final: true, text: "第二段" }),
    ];
    const { container, rerender } = render(<ChatTimeline items={items} />);
    const scroll = container.querySelector(".ja-chat-timeline__scroll");
    expect(scroll).not.toBeNull();
    if (scroll === null) return;

    const scrollTo = vi.fn();
    Object.defineProperties(scroll, {
      clientHeight: { configurable: true, value: 400 },
      scrollHeight: { configurable: true, value: 400 },
      scrollTop: { configurable: true, writable: true, value: 0 },
      scrollTo: { configurable: true, value: scrollTo },
    });
    fireEvent.scroll(scroll);
    scrollTo.mockClear();
    rerender(
      <ChatTimeline
        items={items.map((item, index) => (index === 1 ? { ...item, text: "流式尾部" } : item))}
      />,
    );

    await waitFor(() => expect(scrollTo).toHaveBeenCalled());
    // 清理前让 TanStack Virtual 排空 Debounced Measurement；否则 jsdom 已销毁 Window 后，
    // React 19 仍可能收到一次迟到的 Observer Notification。
    await new Promise((resolve) => setTimeout(resolve, 50));
  });

  it("opens safe https links only through the typed callback", async () => {
    const user = userEvent.setup();
    const onOpenLink = vi.fn();
    const locationBefore = window.location.href;
    render(<MarkdownMessage content="[文档](https://example.com/docs)" onOpenLink={onOpenLink} />);
    await user.click(screen.getByRole("link", { name: "文档" }));
    expect(onOpenLink).toHaveBeenCalledWith("https://example.com/docs");
    expect(window.location.href).toBe(locationBefore);
  });

  it("renders links as inert text without a callback and rejects javascript URLs", () => {
    render(<MarkdownMessage content="[无回调](https://example.com) [危险](javascript:alert(1))" />);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByText("无回调")).toBeVisible();
    expect(screen.getByText("危险")).toBeVisible();
  });

  it("shows image alt text without creating a remote image element", () => {
    const { container } = render(
      <MarkdownMessage content="![远程图片](https://example.com/pixel.png)" />,
    );
    expect(container.querySelector("img")).toBeNull();
    expect(screen.getByText("远程图片")).toBeVisible();
  });

  it("keeps message actions after content surfaces and copies both roles through the typed writer", async () => {
    const user = userEvent.setup();
    const onCopyText = vi.fn(async () => undefined);
    render(
      <ChatTimeline
        items={[
          baseItem({ itemId: "item_user", kind: "user_message", text: "检查复制布局" }),
          baseItem({ text: "结果\n\n```ts\nconst ready = true;\n```" }),
        ]}
        onCopyText={onCopyText}
      />,
    );

    const userMessage = screen.getByRole("article", { name: "用户问题" });
    const finalAnswer = screen.getByRole("article", { name: "最终答复" });
    const userCopy = screen.getByRole("button", { name: "复制消息" });
    const replyCopy = screen.getByRole("button", { name: "复制回复" });
    const userBody = userMessage.querySelector(".ja-chat-message__body");
    const replyBody = finalAnswer.querySelector(".ja-chat-message__body");
    expect(userBody).not.toContainElement(userCopy);
    expect(replyBody).not.toContainElement(replyCopy);
    expect(userBody).not.toBeNull();
    expect(replyBody).not.toBeNull();
    expect(
      userBody!.compareDocumentPosition(userCopy.parentElement!) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      replyBody!.compareDocumentPosition(replyCopy.parentElement!) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(userCopy.parentElement).toHaveAttribute("aria-label", "用户消息操作");
    expect(replyCopy.parentElement).toHaveAttribute("aria-label", "回复操作");

    await user.tab();
    expect(userCopy).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(onCopyText).toHaveBeenCalledWith("检查复制布局");
    await user.click(screen.getByRole("button", { name: "复制代码" }));
    expect(onCopyText).toHaveBeenCalledWith("const ready = true;");
    await user.tab();
    expect(replyCopy).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(onCopyText).toHaveBeenCalledWith("结果\n\n```ts\nconst ready = true;\n```");
  });
});
