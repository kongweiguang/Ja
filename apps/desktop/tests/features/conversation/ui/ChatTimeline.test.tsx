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

  /** 同一 Turn 消费后续输入时必须形成新 exchange，附件和回复不能回流到首条用户气泡。 */
  it("renders every consumed user input as an independent exchange", () => {
    render(
      <ChatTimeline
        items={[
          baseItem({ itemId: "item_user_first", kind: "user_message", text: "先检查代码" }),
          baseItem({ itemId: "item_answer_first", text: "第一轮结论" }),
          baseItem({
            itemId: "item_user_second",
            kind: "user_message",
            text: "再看截图",
            attachments: [
              {
                attachmentId: "att_second",
                displayName: "界面.png",
                sizeBytes: 4096,
                mediaKind: "image",
                mediaType: "image/png",
              },
            ],
          }),
          baseItem({ itemId: "item_answer_second", text: "第二轮结论" }),
        ]}
      />,
    );

    const rows = document.querySelectorAll(".ja-chat-timeline__row");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("先检查代码");
    expect(rows[0]).toHaveTextContent("第一轮结论");
    expect(rows[0]).not.toHaveTextContent("界面.png");
    expect(rows[1]).toHaveTextContent("再看截图");
    expect(rows[1]).toHaveTextContent("界面.png");
    expect(rows[1]).toHaveTextContent("第二轮结论");
  });

  /** Turn 的活动或终态只属于最后一条 USER input，旧 exchange 不得重新出现响应壳。 */
  it("assigns the turn lifecycle only to the latest exchange", () => {
    render(
      <ChatTimeline
        items={[
          baseItem({ itemId: "item_user_first", kind: "user_message", text: "第一条" }),
          baseItem({ itemId: "item_user_second", kind: "user_message", text: "第二条" }),
        ]}
        turns={[{ turnId, threadId: "thr_one", status: "running" }]}
      />,
    );

    const rows = document.querySelectorAll(".ja-chat-timeline__row");
    expect(rows).toHaveLength(2);
    expect(rows[0]?.querySelector('[data-role="response"]')).toBeNull();
    expect(rows[1]?.querySelector('[data-role="response"]')).toHaveAttribute(
      "data-response-state",
      "working",
    );
  });

  /** 历史审批按匹配的 Tool callId 留在原 exchange，不能全部漂移到同一 Turn 的最后一条输入。 */
  it("keeps approvals with the exchange that owns their tool call", () => {
    const firstApproval: ApprovalSummary = {
      approvalId: "appr_first_exchange",
      threadId: "thr_one",
      turnId,
      threadRevision: 1,
      callId: "call_first_exchange",
      toolName: "shell",
      reason: "批准第一条命令",
      expiresAt: "2099-08-17T12:00:00+08:00",
    };
    const secondApproval: ApprovalSummary = {
      ...firstApproval,
      approvalId: "appr_second_exchange",
      callId: "call_second_exchange",
      reason: "批准第二条命令",
    };
    render(
      <ChatTimeline
        items={[
          baseItem({ itemId: "item_user_first", kind: "user_message", text: "第一条" }),
          baseItem({
            itemId: "item_tool_first",
            kind: "tool_call",
            metadata: { callId: "call_first_exchange", toolName: "shell" },
          }),
          baseItem({ itemId: "item_user_second", kind: "user_message", text: "第二条" }),
          baseItem({
            itemId: "item_tool_second",
            kind: "tool_call",
            metadata: { callId: "call_second_exchange", toolName: "shell" },
          }),
        ]}
        approvals={[firstApproval, secondApproval]}
      />,
    );

    const rows = document.querySelectorAll(".ja-chat-timeline__row");
    expect(rows[0]).toHaveTextContent("批准第一条命令");
    expect(rows[0]).not.toHaveTextContent("批准第二条命令");
    expect(rows[1]).toHaveTextContent("批准第二条命令");
    expect(rows[1]).not.toHaveTextContent("批准第一条命令");
  });

  it("用当前 catalog 解析历史 Skill 名称并明确呈现失效状态", () => {
    const item = baseItem({
      itemId: "item_skill",
      kind: "user_message",
      text: "检查界面",
      contextReferences: [{ type: "skill_reference", skillId: "skill_ui" }],
    });
    const { rerender } = render(
      <ChatTimeline
        items={[item]}
        skills={[
          {
            skillId: "skill_ui",
            name: "Apple UI",
            description: "界面生产验收",
            scope: "project",
          },
        ]}
      />,
    );
    expect(screen.getByText("Apple UI")).toBeVisible();
    expect(screen.queryByText("skill_ui")).not.toBeInTheDocument();

    rerender(<ChatTimeline items={[item]} skills={[]} />);
    expect(screen.getByText("Skill 不可用")).toBeVisible();
    expect(screen.queryByText("skill_ui")).not.toBeInTheDocument();
  });

  it("ACK 前立即展示用户问题并直接进入工作动画", () => {
    const { rerender } = render(
      <ChatTimeline
        items={[]}
        localSubmissions={[
          {
            submissionId: "submission:thr_one",
            threadId: "thr_one",
            text: "为什么消息没有立即出现？",
            contextReferences: [],
            attachments: [],
            submittedAt: "2026-08-31T15:00:00.000Z",
            status: "pending",
          },
        ]}
      />,
    );

    const question = screen.getByRole("article", { name: "用户问题" });
    expect(question).toBeVisible();
    expect(question).toHaveTextContent("为什么消息没有立即出现？");
    expect(question).not.toHaveTextContent("正在发送");
    expect(question).not.toHaveAttribute("aria-busy");
    expect(question.querySelector(".ja-chat-message__send-status")).toBeNull();
    const response = screen.getByRole("article", { name: "最终答复" });
    expect(response).toHaveAttribute("data-response-state", "working");
    expect(response).toHaveTextContent("正在工作");
    expect(response.querySelectorAll(".ja-chat-activity-dots > span")).toHaveLength(3);
    expect(screen.queryByText("发送一条消息后，工作过程会显示在这里。")).not.toBeInTheDocument();

    rerender(
      <ChatTimeline
        items={[
          baseItem({
            itemId: "item_persisted_user",
            kind: "user_message",
            text: "为什么消息没有立即出现？",
          }),
        ]}
      />,
    );
    expect(screen.getAllByRole("article", { name: "用户问题" })).toHaveLength(1);
    expect(screen.queryByText("正在发送")).not.toBeInTheDocument();
    expect(screen.queryByText("正在工作")).not.toBeInTheDocument();
  });

  it("发送失败保留用户消息并把提示固定在该消息旁", () => {
    render(
      <ChatTimeline
        items={[]}
        localSubmissions={[
          {
            submissionId: "submission:failed",
            threadId: "thr_one",
            text: "这条消息必须留下",
            contextReferences: [],
            attachments: [],
            submittedAt: "2026-09-02T00:00:00.000Z",
            status: "failed",
            error: "发送失败，请检查运行时连接后重试。",
          },
        ]}
      />,
    );

    const question = screen.getByRole("article", { name: "用户问题" });
    expect(question).toHaveTextContent("这条消息必须留下");
    expect(question).toContainElement(screen.getByRole("alert"));
    expect(screen.getByRole("alert")).toHaveTextContent("发送失败，请检查运行时连接后重试。");
    expect(screen.queryByRole("article", { name: "最终答复" })).not.toBeInTheDocument();
  });

  /** 附件本身就是有效用户输入；空正文的本地 submission 仍需立即显示且允许预览。 */
  it("keeps an attachment-only local submission visible", async () => {
    const onOpenAttachmentPreview = vi.fn();
    render(
      <ChatTimeline
        items={[]}
        localSubmissions={[
          {
            submissionId: "submission:attachment-only",
            threadId: "thr_one",
            text: "",
            contextReferences: [],
            attachments: [
              {
                attachmentId: "att_local_only",
                displayName: "截图.png",
                sizeBytes: 8192,
                mediaKind: "image",
                mediaType: "image/png",
                thumbnailUrl: "ja-attachment://thumb_local_only",
              },
            ],
            submittedAt: "2026-09-02T00:00:00.000Z",
            status: "pending",
          },
        ]}
        onOpenAttachmentPreview={onOpenAttachmentPreview}
      />,
    );

    const question = screen.getByRole("article", { name: "用户问题" });
    expect(question).toHaveTextContent("截图.png");
    expect(question).toHaveTextContent("图片 · 8.0 KB");
    expect(question.querySelector("img")).toHaveAttribute(
      "src",
      "ja-attachment://thumb_local_only",
    );
    await userEvent.click(screen.getByRole("button", { name: "预览附件 截图.png" }));
    expect(onOpenAttachmentPreview).toHaveBeenCalledWith(
      {
        attachmentId: "att_local_only",
        displayName: "截图.png",
        mediaKind: "image",
        threadId: "thr_one",
        authorization: { kind: "draft" },
      },
      expect.any(HTMLButtonElement),
    );
  });

  it("按提交时间合并失败本地消息与后续权威消息", () => {
    render(
      <ChatTimeline
        items={[
          baseItem({
            itemId: "item_later",
            turnId: "turn_later",
            kind: "user_message",
            text: "后来成功的消息",
            createdAt: "2026-09-02T00:00:02.000Z",
          }),
        ]}
        localSubmissions={[
          {
            submissionId: "submission:earlier-failed",
            threadId: "thr_one",
            text: "先前失败的消息",
            contextReferences: [],
            attachments: [],
            submittedAt: "2026-09-02T00:00:01.000Z",
            status: "failed",
            error: "发送失败，请检查运行时连接后重试。",
          },
        ]}
      />,
    );

    expect(
      screen.getAllByRole("article", { name: "用户问题" }).map((item) => item.textContent),
    ).toEqual([
      expect.stringContaining("先前失败的消息"),
      expect.stringContaining("后来成功的消息"),
    ]);
  });

  /** 响应壳的 DOM identity 贯穿完整生命周期，防止首字和 terminal 各自挂载造成视觉跳变。 */
  it("在同一响应节点内完成 working、streaming 与 completed 转换", () => {
    const runningTurn = { turnId, threadId: "thr_one", status: "running" as const };
    const { rerender } = render(<ChatTimeline items={[]} turns={[runningTurn]} />);

    const response = screen.getByRole("article", { name: "最终答复" });
    expect(response).toHaveAttribute("data-response-state", "working");
    expect(response).toHaveClass("ja-chat-message-draft");
    expect(response.querySelectorAll(".ja-chat-activity-dots > span")).toHaveLength(3);
    const workingStatus = response.querySelector(".ja-chat-response__status");
    expect(workingStatus?.firstElementChild?.textContent).toBe("正在工作");
    expect(workingStatus?.lastElementChild).toHaveClass("ja-chat-activity-dots");
    expect(screen.queryByRole("region", { name: "工作过程" })).not.toBeInTheDocument();

    rerender(
      <ChatTimeline
        items={[baseItem({ itemId: "item_stream", status: "in_progress", text: "第一段" })]}
        turns={[runningTurn]}
      />,
    );
    const streamingResponse = screen.getByRole("article", { name: "最终答复" });
    expect(streamingResponse).toBe(response);
    expect(streamingResponse).toHaveAttribute("data-response-state", "streaming");
    expect(streamingResponse).toHaveTextContent("第一段");
    expect(streamingResponse).toHaveTextContent("正在回复");
    expect(streamingResponse.querySelectorAll(".ja-chat-activity-dots > span")).toHaveLength(3);
    expect(
      streamingResponse.querySelector(".ja-chat-response__content")?.nextElementSibling,
    ).toHaveClass("ja-chat-response__status");

    rerender(
      <ChatTimeline
        items={[baseItem({ itemId: "item_stream", text: "第一段继续完成" })]}
        turns={[{ ...runningTurn, status: "completed" }]}
      />,
    );
    const completedResponse = screen.getByRole("article", { name: "最终答复" });
    expect(completedResponse).toBe(response);
    expect(completedResponse).toHaveAttribute("data-response-state", "completed");
    expect(completedResponse).not.toHaveClass("ja-chat-message-draft");
    expect(completedResponse).toHaveTextContent("第一段继续完成");
    expect(screen.queryByText("正在回复")).not.toBeInTheDocument();
    expect(completedResponse.querySelector(".ja-chat-activity-dots")).toBeNull();
    expect(document.querySelectorAll('[data-role="final"]')).toHaveLength(1);
  });

  /** 审批是权威暂停态，状态恢复后才重新出现工作呼吸点，且审批本身仍进入真实工作过程。 */
  it("无 USER item 的内部 Turn 仍提供稳定审批行并在恢复后继续工作态", async () => {
    const user = userEvent.setup();
    const onApprovalDecision = vi.fn();
    const approval: ApprovalSummary = {
      approvalId: "appr_waiting",
      threadId: "thr_one",
      turnId,
      threadRevision: 1,
      callId: "call_waiting",
      toolName: "shell",
      reason: "运行测试",
      expiresAt: "2099-08-17T12:00:00+08:00",
    };
    const waitingTurn = { turnId, threadId: "thr_one", status: "waiting_approval" as const };
    const { rerender } = render(
      <ChatTimeline
        items={[]}
        turns={[waitingTurn]}
        approvals={[approval]}
        onApprovalDecision={onApprovalDecision}
      />,
    );

    expect(
      document.querySelector(`.ja-chat-timeline__row[data-turn-id="${turnId}"]`),
    ).toBeVisible();
    const response = screen.getByRole("article", { name: "最终答复" });
    expect(response).toHaveAttribute("data-response-state", "waiting");
    expect(response).toHaveTextContent("等待你的确认");
    expect(response.querySelector(".ja-chat-activity-dots")).toBeNull();
    expect(screen.getByRole("region", { name: "工作过程" })).toBeVisible();
    const approve = screen.getByRole("button", { name: /^批准$/ });
    expect(approve).toBeEnabled();
    await user.click(approve);
    expect(onApprovalDecision).toHaveBeenCalledWith(approval, "approve");

    rerender(
      <ChatTimeline
        items={[]}
        turns={[{ ...waitingTurn, status: "running" }]}
        approvals={[approval]}
      />,
    );
    expect(screen.getByRole("article", { name: "最终答复" })).toBe(response);
    expect(response).toHaveAttribute("data-response-state", "working");
    expect(response.querySelectorAll(".ja-chat-activity-dots > span")).toHaveLength(3);
  });

  /** Terminal 状态只保留静态结论，失败绝不能继续伪装成最终答复。 */
  it("失败、取消和历史完成回复都不会保留活动动画标识", () => {
    const { rerender } = render(
      <ChatTimeline items={[]} turns={[{ turnId, threadId: "thr_one", status: "failed" }]} />,
    );
    const response = screen.getByRole("article", { name: "失败说明" });
    expect(response).toHaveAttribute("data-response-state", "failed");
    expect(response).toHaveAttribute("data-role", "failure");
    expect(response).toHaveTextContent("任务未完成");
    expect(response).toHaveTextContent("本轮没有生成最终答复");
    expect(screen.queryByRole("article", { name: "最终答复" })).not.toBeInTheDocument();
    expect(response).not.toHaveClass("ja-chat-message-draft");
    expect(response.querySelector(".ja-chat-activity-dots")).toBeNull();

    rerender(
      <ChatTimeline items={[]} turns={[{ turnId, threadId: "thr_one", status: "cancelled" }]} />,
    );
    expect(response).toHaveAttribute("data-response-state", "cancelled");
    expect(response).toHaveTextContent("已取消");
    expect(response.querySelector(".ja-chat-activity-dots")).toBeNull();

    rerender(<ChatTimeline items={[baseItem({ itemId: "item_history", text: "历史答复" })]} />);
    expect(response).toHaveAttribute("data-response-state", "completed");
    expect(response).not.toHaveClass("ja-chat-message-draft");
    expect(response).not.toHaveClass("ja-chat-message-new");
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

  /** Turn 是整轮状态 owner，覆盖七态并锁定局部失败不能抢占运行态。 */
  it("完整映射七种权威 Turn 状态且始终使用稳定标题", () => {
    const step = baseItem({
      itemId: "item_state",
      kind: "tool_call",
      title: "读取状态",
      status: "completed",
      metadata: {
        presentation: {
          kind: "read",
          title: "读取状态",
          status: "success",
          relativePaths: ["state.json"],
          truncated: false,
        },
      },
    });
    const cases = [
      { status: "queued" as const, state: "queued", label: "排队中" },
      { status: "running" as const, state: "active", label: "进行中…" },
      { status: "waiting_approval" as const, state: "waiting", label: "等待确认" },
      { status: "suspended" as const, state: "suspended", label: "运行被中断" },
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
      kind: "tool_call",
      title: "读取耗时",
      status: "completed",
      durationMs: 7_300,
      metadata: {
        presentation: {
          kind: "read",
          title: "读取耗时",
          status: "success",
          relativePaths: ["duration.json"],
          truncated: false,
        },
      },
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

  it("无标题展示思考摘要并隐藏协议阶段、模型轮次和截断指标", () => {
    const commentary = baseItem({
      itemId: "item_progress",
      kind: "commentary",
      title: "回复过程",
      text: "正在分析公开上下文",
      status: "in_progress",
      metadata: { phase: "assistant_progress", modelRound: 2, truncated: true },
    });
    const tool = baseItem({
      itemId: "item_read",
      kind: "tool_call",
      status: "completed",
      metadata: {
        presentation: {
          kind: "read",
          title: "读取文件",
          status: "success",
          relativePaths: ["src/main.ts"],
          truncated: false,
        },
      },
    });
    const followUpCommentary = baseItem({
      ...commentary,
      itemId: "item_progress_follow_up",
      text: "正在验证读取结果",
    });
    const { rerender } = render(<WorkProcess steps={[commentary, tool, followUpCommentary]} />);

    expect(screen.getByRole("button", { name: /工作过程.*1 步/u })).toBeVisible();
    expect(screen.queryByText("回复过程")).not.toBeInTheDocument();
    const firstCommentary = screen.getByText("正在分析公开上下文");
    const toolRow = screen.getByRole("button", { name: /读取，src\/main\.ts，完成/u });
    const followUp = screen.getByText("正在验证读取结果");
    expect(firstCommentary).toBeVisible();
    expect(followUp).toBeVisible();
    expect(
      firstCommentary.compareDocumentPosition(toolRow) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(toolRow.compareDocumentPosition(followUp) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(
      0,
    );
    expect(screen.queryByText("模型第 2 轮")).not.toBeInTheDocument();
    expect(screen.queryByText("仅显示部分文件")).not.toBeInTheDocument();
    expect(screen.queryByText("assistant_progress")).not.toBeInTheDocument();
    expect(screen.queryByText("reasoning_summary")).not.toBeInTheDocument();

    rerender(<WorkProcess steps={[commentary]} />);
    expect(screen.getByRole("region", { name: "工作过程" })).toBeVisible();
    expect(screen.getByText("正在分析公开上下文")).toBeVisible();
    expect(document.querySelector(".ja-work-step__icon")).toBeNull();
    expect(document.querySelector(".ja-work-step__header")).toBeNull();
    expect(document.querySelector(".ja-work-step__duration")).toBeNull();
  });

  /** started 增量只替换既有 Tool presentation，WorkProcess 节点不应重建或继续显示等待执行。 */
  it("原位把 Tool 从等待执行更新为进行中", () => {
    const pending = baseItem({
      itemId: "item_tool_started",
      kind: "tool_call",
      title: "运行命令",
      status: "in_progress",
      metadata: {
        callId: "call_started",
        toolName: "shell",
        presentation: {
          kind: "shell",
          title: "运行命令",
          status: "pending",
          relativePaths: [],
          command: "pnpm test",
          relativeCwd: "workspace",
          truncated: false,
        },
      },
    });
    const { rerender } = render(<WorkProcess steps={[pending]} />);
    const region = screen.getByRole("region", { name: "工作过程" });
    expect(screen.getByRole("button", { name: /执行命令，pnpm test，等待执行/ })).toHaveAttribute(
      "aria-expanded",
      "false",
    );

    rerender(
      <WorkProcess
        steps={[
          {
            ...pending,
            metadata: {
              ...pending.metadata,
              presentation: {
                kind: "shell",
                title: "运行命令",
                status: "running",
                relativePaths: [],
                command: "pnpm test",
                relativeCwd: "workspace",
                truncated: false,
              },
            },
          },
        ]}
      />,
    );
    expect(screen.getByRole("region", { name: "工作过程" })).toBe(region);
    expect(screen.getByRole("button", { name: /执行命令，pnpm test，进行中/ })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    expect(region).not.toHaveTextContent("状态：等待执行");
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
            itemId: "item_user",
            kind: "user_message",
            text: "检查附件",
            attachments: [
              {
                attachmentId: "att_snapshot",
                displayName: "设计稿.pdf",
                sizeBytes: 2048,
                mediaKind: "pdf",
                mediaType: "application/pdf",
              },
            ],
          }),
          baseItem({ itemId: "item_answer", text: "已检查" }),
        ]}
      />,
    );

    expect(screen.queryByRole("button", { name: /工作过程/ })).not.toBeInTheDocument();
    expect(screen.getByRole("list", { name: "附件" })).toBeVisible();
    expect(screen.getByText("设计稿.pdf")).toBeVisible();
    expect(screen.getByText("PDF · 2.0 KB")).toBeVisible();
    expect(screen.queryByRole("button", { name: "预览附件 设计稿.pdf" })).not.toBeInTheDocument();
  });

  /** 历史附件只为服务端明确分类的图片和文本提供对象级预览入口。 */
  it("opens previewable history attachments with bound Thread identity", async () => {
    const onOpenAttachmentPreview = vi.fn();
    render(
      <ChatTimeline
        items={[
          baseItem({
            itemId: "item_image_message",
            threadId: "thr_preview",
            kind: "user_message",
            attachments: [
              {
                attachmentId: "att_preview",
                displayName: "界面.png",
                sizeBytes: 4096,
                mediaKind: "image",
                mediaType: "image/png",
              },
            ],
          }),
        ]}
        onOpenAttachmentPreview={onOpenAttachmentPreview}
      />,
    );

    await userEvent.click(screen.getByRole("button", { name: "预览附件 界面.png" }));
    expect(onOpenAttachmentPreview).toHaveBeenCalledWith(
      {
        attachmentId: "att_preview",
        displayName: "界面.png",
        mediaKind: "image",
        threadId: "thr_preview",
        authorization: { kind: "thread", threadId: "thr_preview" },
      },
      expect.any(HTMLButtonElement),
    );
  });

  /** 未注入真实预览动作时图片仍显示附件事实，但不能渲染无操作按钮。 */
  it("does not create a preview button without a preview action", () => {
    render(
      <ChatTimeline
        items={[
          baseItem({
            itemId: "item_image_without_action",
            kind: "user_message",
            attachments: [
              {
                attachmentId: "att_without_action",
                displayName: "只读图片.png",
                sizeBytes: 1024,
                mediaKind: "image",
                mediaType: "image/png",
              },
            ],
          }),
        ]}
      />,
    );

    expect(screen.getByText("只读图片.png")).toBeVisible();
    expect(screen.queryByRole("button", { name: "预览附件 只读图片.png" })).not.toBeInTheDocument();
  });

  it("shows turn duration and explicit terminal failure without step metrics", () => {
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
    expect(
      screen.queryByText(/2 个文件|src\/Main\.tsx|docs\/readme\.md|\+7|−3/u),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("TURN_FAILED");
  });

  it("将可重试故障显示为非自动重放的重新编辑入口", async () => {
    const user = userEvent.setup();
    const onPrepareRetry = vi.fn();
    render(
      <ChatTimeline
        turns={[
          {
            turnId,
            threadId: "thr_one",
            status: "failed",
            startedAt: "2099-08-17T12:00:00+08:00",
            completedAt: "2099-08-17T12:00:01+08:00",
            error: { code: "MODEL_UNAVAILABLE", retryable: true },
          },
        ]}
        items={[
          baseItem({
            itemId: "item_user_for_retry",
            kind: "user_message",
            text: "继续检查这个问题",
          }),
          baseItem({ itemId: "item_failed", status: "failed" }),
        ]}
        onPrepareRetry={onPrepareRetry}
      />,
    );

    const failure = screen.getByRole("article", { name: "失败说明" });
    expect(failure).toHaveAttribute("data-role", "failure");
    expect(screen.getByRole("alert")).toHaveTextContent("模型服务暂时不可用");
    expect(screen.getByRole("alert")).toHaveTextContent("本轮没有生成最终答复");
    expect(screen.getByRole("alert")).toHaveTextContent("MODEL_UNAVAILABLE");
    expect(screen.getByRole("alert")).toHaveTextContent(
      "只恢复原问题，不会自动发送或重放已执行的工具",
    );
    expect(screen.queryByRole("article", { name: "最终答复" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "重新编辑" }));
    expect(onPrepareRetry).toHaveBeenCalledTimes(1);
    expect(onPrepareRetry).toHaveBeenCalledWith(turnId, "继续检查这个问题");
  });

  it("模型协议故障不武断归因且仍允许安全恢复原文", async () => {
    const user = userEvent.setup();
    const onPrepareRetry = vi.fn();
    render(
      <ChatTimeline
        turns={[
          {
            turnId,
            threadId: "thr_one",
            status: "failed",
            startedAt: "2099-08-17T12:00:00+08:00",
            completedAt: "2099-08-17T12:00:01+08:00",
            error: { code: "MODEL_PROTOCOL_ERROR", retryable: false },
          },
        ]}
        items={[
          baseItem({
            itemId: "item_protocol_user",
            kind: "user_message",
            text: "继续完成剩余工作",
          }),
          baseItem({ itemId: "item_protocol_failed", status: "failed" }),
        ]}
        onPrepareRetry={onPrepareRetry}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("模型响应格式有误或不完整");
    expect(screen.getByRole("alert")).toHaveTextContent("若持续失败，请查看运行日志中的具体原因");
    expect(screen.getByRole("alert")).toHaveTextContent("MODEL_PROTOCOL_ERROR");
    await user.click(screen.getByRole("button", { name: "重新编辑" }));
    expect(onPrepareRetry).toHaveBeenCalledWith(turnId, "继续完成剩余工作");
  });

  it("将预算耗尽解释为未完成并给出调整范围后的恢复路径", () => {
    render(
      <ChatTimeline
        turns={[
          {
            turnId,
            threadId: "thr_one",
            status: "failed",
            error: { code: "BUDGET_EXCEEDED", retryable: false },
          },
        ]}
        items={[baseItem({ itemId: "item_budget_failed", status: "failed" })]}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("达到执行上限前没有完成回复");
    expect(screen.getByRole("alert")).toHaveTextContent("缩小任务范围");
    expect(screen.getByRole("alert")).toHaveTextContent("BUDGET_EXCEEDED");
  });

  it("保留失败前的公开内容但明确标为不完整回复", () => {
    render(
      <ChatTimeline
        turns={[
          {
            turnId,
            threadId: "thr_one",
            status: "failed",
            error: { code: "MODEL_UNAVAILABLE", retryable: true },
          },
        ]}
        items={[
          baseItem({
            itemId: "item_partial_failed",
            status: "failed",
            final: true,
            text: "已经生成但尚未完成的内容",
          }),
        ]}
      />,
    );

    const failure = screen.getByRole("article", { name: "失败说明" });
    expect(failure).toHaveTextContent("以下内容在失败前生成，可能不完整，不能视为最终答复");
    expect(failure).toHaveTextContent("已经生成但尚未完成的内容");
    expect(failure).toHaveAttribute("data-role", "failure");
    expect(document.querySelectorAll('[data-role="final"]')).toHaveLength(0);
  });

  it("把运行时失败正文显示为已保存的失败回复，同时保留错误状态和恢复动作", () => {
    const onPrepareRetry = vi.fn();
    render(
      <ChatTimeline
        turns={[
          {
            turnId,
            threadId: "thr_one",
            status: "failed",
            error: { code: "MODEL_UNAVAILABLE", retryable: true },
          },
        ]}
        items={[
          baseItem({
            itemId: "item_user_for_retry",
            kind: "user_message",
            text: "请读取文件",
          }),
          baseItem({
            itemId: "item_failure_reply",
            status: "failed",
            final: true,
            text: "本轮未能完成：模型服务暂时不可用。",
            metadata: { failureReply: true },
          }),
        ]}
        onPrepareRetry={onPrepareRetry}
      />,
    );

    const failure = screen.getByRole("article", { name: "失败说明" });
    expect(failure).toHaveTextContent("本轮未能完成：模型服务暂时不可用");
    expect(failure).toHaveTextContent("本轮失败原因已保存");
    expect(failure).not.toHaveTextContent("可能不完整");
    expect(failure).toHaveTextContent("MODEL_UNAVAILABLE");
    expect(failure).toHaveAttribute("data-role", "failure");
    expect(document.querySelectorAll('[data-role="final"]')).toHaveLength(0);
    expect(screen.getByRole("button", { name: "重新编辑" })).toBeVisible();
  });

  it("工作过程成功摘要不夹带文件变更、同步状态或零修改占位", () => {
    const step = baseItem({
      itemId: "item_summary",
      kind: "tool_call",
      title: "读取摘要",
      status: "completed",
      metadata: {
        presentation: {
          kind: "read",
          title: "读取摘要",
          status: "success",
          relativePaths: ["summary.json"],
          truncated: false,
        },
      },
    });
    const availableTurn = {
      turnId,
      threadId: "thr_one",
      status: "completed" as const,
      startedAt: "2099-08-17T12:00:00Z",
      completedAt: "2099-08-17T12:00:01Z",
      changeSet: {
        state: "complete" as const,
        incompleteReasons: [],
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
            state: "partial",
            incompleteReasons: ["capture_failed"],
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

  it("把 Tool 动作与首个目标压缩为单行折叠入口", () => {
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
    expect(screen.getByRole("button", { name: /读取，src\/read\.ts，进行中/ })).toBeVisible();
    expect(document.querySelectorAll(".ja-work-step__header")).toHaveLength(0);

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
    expect(screen.getByRole("button", { name: /编辑，src\/edit\.ts，进行中/ })).toBeVisible();

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
    expect(screen.getByRole("button", { name: /执行命令，package\.json，进行中/ })).toBeVisible();
  });

  /** 成功命令默认只保留可扫描摘要，用户展开后再读取命令事实与十行输出预览。 */
  it("用单行命令折叠入口按需展示分流输出和退出事实", async () => {
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
                status: "success",
                relativePaths: [],
                command: "pnpm test",
                relativeCwd: "workspace",
                stdout,
                stderr: "测试告警",
                exitCode: 1,
                durationMs: 1_250,
                truncated: false,
              },
            },
            status: "completed",
          }),
        ]}
      />,
    );

    expect(screen.queryByText("工作目录：workspace")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /工作过程/ }));

    const detailsTrigger = screen.getByRole("button", { name: /执行命令，pnpm test，完成/ });
    expect(detailsTrigger).toBeVisible();
    expect(detailsTrigger).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText("工作目录：workspace")).not.toBeInTheDocument();

    await user.click(detailsTrigger);
    const output = document.querySelector(".ja-tool-details__output");
    expect(detailsTrigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getAllByText("pnpm test")).toHaveLength(1);
    expect(screen.queryByText("命令与结果")).not.toBeInTheDocument();
    expect(screen.getByText("工作目录：workspace")).toBeVisible();
    expect(output).toHaveTextContent("stdout");
    expect(output).toHaveTextContent("输出 9");
    expect(output).not.toHaveTextContent("输出 10");
    expect(screen.getByText("退出码 1")).toBeVisible();
    expect(screen.getByText("状态：完成")).toBeVisible();
    expect(document.querySelector(".ja-tool-details__facts")).toHaveTextContent("1.3 s");
    await user.click(screen.getByRole("button", { name: /展开全部/ }));
    expect(output).toHaveTextContent("输出 12");
    expect(output).toHaveTextContent("stderr");
    expect(output).toHaveTextContent("测试告警");
  });

  /** 错误结果自动展开，确保二级折叠不会把唯一诊断和恢复线索隐藏起来。 */
  it("命令失败时自动展开结果", () => {
    render(
      <WorkProcess
        steps={[
          baseItem({
            itemId: "item_shell_failed",
            kind: "tool_call",
            title: "运行命令",
            status: "failed",
            metadata: {
              callId: "call_shell_failed",
              toolName: "shell",
              presentation: {
                kind: "shell",
                title: "运行命令",
                status: "error",
                relativePaths: [],
                command: "pnpm test",
                stderr: "测试失败",
                exitCode: 1,
                truncated: false,
              },
            },
          }),
        ]}
      />,
    );

    expect(screen.getByRole("button", { name: /执行命令，pnpm test，失败/ })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    expect(screen.getByText(/测试失败/)).toBeVisible();
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
    await user.click(screen.getByRole("button", { name: /读取，src\/main\.ts，完成/ }));
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
    await user.click(screen.getByRole("button", { name: /读取，src\/main\.ts，完成/ }));
    await user.click(screen.getByRole("button", { name: "加载完整输出" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("无法读取完整输出");
    expect(screen.queryByText(/native secret/)).not.toBeInTheDocument();
  });

  it("展示冻结修改事实，并把查看动作交给 Workbench 页面", async () => {
    const user = userEvent.setup();
    const turn = { turnId, threadId: "thr_one", status: "completed" as const };
    const available = {
      state: "complete" as const,
      incompleteReasons: [],
      files: [
        {
          path: "src/new.ts",
          status: "modified" as const,
          additions: 4,
          deletions: 2,
          binary: false,
          truncated: false,
        },
        {
          path: "assets/logo.png",
          status: "modified" as const,
          additions: 0,
          deletions: 0,
          binary: true,
          truncated: false,
        },
        {
          path: "src/third.ts",
          status: "added" as const,
          additions: 1,
          deletions: 0,
          binary: false,
          truncated: false,
        },
        {
          path: "src/fourth.ts",
          status: "deleted" as const,
          additions: 0,
          deletions: 1,
          binary: false,
          truncated: false,
        },
      ],
      stats: { files: 4, additions: 5, deletions: 3, binaryFiles: 1, truncated: false },
      artifactId: "artifact_diff",
    };
    const review = vi.fn();
    const { rerender } = render(
      <TurnChangesCard turn={turn} changeSet={available} onReview={review} />,
    );
    expect(screen.getByText("已编辑 4 个文件")).toBeVisible();
    expect(screen.getByText("src/new.ts")).toBeVisible();
    expect(screen.getByText("二进制")).toBeVisible();
    expect(screen.queryByText("src/fourth.ts")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "查看 src/new.ts 的修改" }));
    expect(review).toHaveBeenLastCalledWith("src/new.ts");
    await user.click(screen.getByRole("button", { name: "再显示 1 个文件" }));
    expect(screen.getByText("src/fourth.ts")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "查看修改" }));
    expect(review).toHaveBeenCalledTimes(2);
    expect(review).toHaveBeenLastCalledWith();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    rerender(<TurnChangesCard turn={turn} changeSet={{ ...available, artifactId: undefined }} />);
    expect(screen.queryByRole("button", { name: "查看修改" })).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "查看 src/new.ts 的修改" }),
    ).not.toBeInTheDocument();
    rerender(
      <TurnChangesCard
        turn={turn}
        changeSet={{
          state: "complete",
          incompleteReasons: [],
          files: [],
          stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
        }}
      />,
    );
    expect(screen.queryByRole("region", { name: "修改记录" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "查看修改" })).not.toBeInTheDocument();
  });

  it("为每个终态 Turn 保留修改卡并按各自身份触发查看", async () => {
    const user = userEvent.setup();
    const review = vi.fn();
    const changeSet = {
      state: "complete" as const,
      incompleteReasons: [],
      files: [
        {
          path: "src/latest.ts",
          status: "modified" as const,
          additions: 1,
          deletions: 0,
          binary: false,
          truncated: false,
        },
      ],
      stats: { files: 1, additions: 1, deletions: 0, binaryFiles: 0, truncated: false },
      artifactId: "artifact_latest",
    };
    render(
      <ChatTimeline
        items={[
          baseItem({ itemId: "item_old", turnId: "turn_old", final: true, text: "旧答复" }),
          baseItem({ itemId: "item_latest", turnId: "turn_latest", final: true, text: "新答复" }),
        ]}
        turns={[
          {
            turnId: "turn_old",
            threadId: "thr_one",
            status: "completed",
            changeSet: {
              ...changeSet,
              files: [{ ...changeSet.files[0]!, path: "src/old.ts" }],
            },
          },
          { turnId: "turn_latest", threadId: "thr_one", status: "completed", changeSet },
        ]}
        onReviewTurn={review}
      />,
    );

    expect(screen.getAllByRole("region", { name: "修改记录" })).toHaveLength(2);
    expect(screen.getByText("src/old.ts")).toBeVisible();
    const latestPath = screen.getByText("src/latest.ts");
    expect(latestPath).toBeVisible();
    const latestReview = latestPath
      .closest(".ja-turn-changes")
      ?.querySelector<HTMLButtonElement>("button");
    expect(latestReview).not.toBeNull();
    await user.click(latestReview!);
    expect(review).toHaveBeenCalledWith(
      expect.objectContaining({ turnId: "turn_latest" }),
      changeSet,
      undefined,
    );
  });

  it("流式答复原位显示，并固定工作过程、答复与可靠非零变更的顺序", async () => {
    const runningTurn = {
      turnId,
      threadId: "thr_one",
      status: "running" as const,
    };
    const completedTurn = {
      ...runningTurn,
      status: "completed" as const,
      changeSet: {
        state: "complete" as const,
        incompleteReasons: [],
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
            kind: "tool_call",
            title: "读取结果",
            status: "completed",
            createdAt: "2099-08-17T12:00:01Z",
            metadata: {
              presentation: {
                kind: "read",
                title: "读取结果",
                status: "success",
                relativePaths: ["result.json"],
                truncated: false,
              },
            },
          }),
          baseItem({
            itemId: "item_final",
            final: true,
            text: "最终结果",
            createdAt: "2099-08-17T12:00:02Z",
          }),
        ]}
      />,
    );
    const process = await screen.findByRole("region", { name: "工作过程" });
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
              state: "complete",
              incompleteReasons: [],
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
              state: "partial",
              incompleteReasons: ["capture_failed"],
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

  /** 回到底部入口必须悬浮在 Timeline Viewport 上，不能进入滚动内容并反向改变 scrollHeight。 */
  it("keeps the jump-to-latest control outside the scroll content", async () => {
    const { container } = render(
      <ChatTimeline
        items={[
          baseItem({ itemId: "item_1", text: "较早消息" }),
          baseItem({ itemId: "item_2", final: true, text: "最新消息" }),
        ]}
      />,
    );
    const timeline = container.querySelector(".ja-chat-timeline");
    const scroll = container.querySelector(".ja-chat-timeline__scroll");
    expect(timeline).not.toBeNull();
    expect(scroll).not.toBeNull();
    if (timeline === null || scroll === null) return;

    Object.defineProperties(scroll, {
      clientHeight: { configurable: true, value: 400 },
      scrollHeight: { configurable: true, value: 4_000 },
      scrollTop: { configurable: true, writable: true, value: 100 },
    });
    fireEvent.scroll(scroll);

    const jumpToLatest = await screen.findByRole("button", { name: "回到最新" });
    expect(timeline).toContainElement(jumpToLatest);
    expect(scroll).not.toContainElement(jumpToLatest);
  });

  /** 首格向上滚动尚未越过底部阈值时，也必须先于下一段 Stream 更新解除自动跟随。 */
  it("releases live-tail following as soon as the user wheels upward", async () => {
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
      scrollHeight: { configurable: true, value: 420 },
      scrollTop: { configurable: true, writable: true, value: 20 },
      scrollTo: { configurable: true, value: scrollTo },
    });
    fireEvent.scroll(scroll);
    await new Promise((resolve) => setTimeout(resolve, 20));
    scrollTo.mockClear();

    fireEvent.wheel(scroll, { deltaY: -40 });
    expect(await screen.findByRole("button", { name: "回到最新" })).toBeVisible();
    rerender(
      <ChatTimeline
        items={items.map((item, index) => (index === 1 ? { ...item, text: "流式尾部" } : item))}
      />,
    );

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(scrollTo).not.toHaveBeenCalled();
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
