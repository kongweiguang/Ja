// @author kongweiguang
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ConversationPane } from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/ConversationPane";
import {
  emptySession,
  emptyTurn,
} from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/state";

/**
 * @author kongweiguang
 * 使用真实工作台组件和原生问题身份，验证可读摘要不会生成另一份审批参数或答案。
 */
function renderApproval(
  kind: "approval" | "input" = "approval",
  extra: Record<string, unknown> = {},
) {
  const turn = emptyTurn("turn_approval", "解读并保存资料", []);
  turn.state = "WAITING_APPROVAL";
  turn.attachments = [
    {
      id: "att_original",
      threadId: "thr_approval",
      fileName: "合同.pdf",
      mediaType: "application/pdf",
      sizeBytes: 123,
      sha256: "a".repeat(64),
      createdAt: "2026-10-06T00:00:00Z",
      referenced: true,
    },
  ];
  const parameters = {
    title: "企业资料摘要",
    body: "资料事实：费用为 128.50 USD。\n不确定项：未提供付款期限。",
    sourceAttachmentIds: ["att_original"],
    ...extra,
  };
  const prompt = `允许执行 save_note？\n${JSON.stringify(parameters)}`;
  turn.wait = {
    interactionId: "interaction_frozen",
    interactionRevision: 3,
    kind,
    status: "PENDING",
    questions: [
      {
        questionId: "question_approval",
        prompt,
        type: "SINGLE",
        required: true,
        allowFreeText: false,
        options: [
          {
            optionId: "option_approve",
            label: "批准",
            description: "允许执行此次已冻结的工具调用",
            recommended: false,
          },
          {
            optionId: "option_deny",
            label: "拒绝",
            description: "不执行此次工具调用",
            recommended: false,
          },
        ],
      },
    ],
  };
  const onAnswer = vi.fn(async () => undefined);
  const view = render(
    <ConversationPane
      threadId="thr_approval"
      model="test-model"
      session={{ ...emptySession(), historyLoaded: true, turns: [turn] }}
      onLoadMoreHistory={vi.fn(async () => undefined)}
      onSubmit={vi.fn()}
      onSessionChange={vi.fn()}
      onAnswer={onAnswer}
      onResume={vi.fn(async () => undefined)}
      onRetry={vi.fn()}
      onControl={vi.fn(async () => undefined)}
      onWorkbench={vi.fn()}
      pendingRecoveries={{}}
      onRefreshRecoveries={vi.fn(async () => undefined)}
    />,
  );
  return { ...view, prompt, parameters, turn, onAnswer };
}

/** 移除真实组件的焦点和滚动副作用，避免上一份审批影响下一例。 */
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Spring approval presentation", () => {
  /** 用户先读最终标题、正文与资料名；展开仍可核对原始参数，提交只使用原问题身份。 */
  it("审批先展示业务内容，技术参数折叠并保持原始审批身份", async () => {
    const user = userEvent.setup();
    const { container, prompt, parameters, turn, onAnswer } = renderApproval();
    expect(screen.getByText("是否保存这份笔记？")).toBeVisible();
    expect(screen.getByText(parameters.title)).toBeVisible();
    expect(screen.getByText(/资料事实：费用为 128.50 USD/, { selector: "dd" })).toBeVisible();
    expect(screen.getByText("关联资料 · 1 份")).toBeVisible();
    expect(screen.getAllByText("合同.pdf").at(-1)).toBeVisible();
    const original = container.querySelector(".approval-technical pre")!;
    expect(original.textContent).toBe(prompt);
    expect(original).not.toBeVisible();
    await user.click(screen.getByText("技术详情"));
    expect(original).toBeVisible();
    await user.click(screen.getByRole("radio", { name: "批准" }));
    await user.click(screen.getByRole("button", { name: "确认并继续" }));
    expect(onAnswer).toHaveBeenCalledWith("thr_approval", turn, [
      {
        questionId: "question_approval",
        optionIds: ["option_approve"],
        freeText: null,
        skipped: false,
      },
    ]);
  });

  /** 普通澄清即使具有相同文本，也不能被解释成业务审批。 */
  it("相同文本的普通问题保留完整内容", () => {
    const { container, prompt } = renderApproval("input");
    expect(container.querySelector("legend")?.textContent).toContain(prompt);
    expect(container.querySelector(".approval-note")).toBeNull();
  });

  /** 未识别的授权参数必须可见，不能被只展示已知字段的摘要悄悄省略。 */
  it("出现额外参数时保留原始审批以避免隐瞒授权范围", () => {
    const { container, prompt } = renderApproval("approval", { overwrite: true });
    expect(container.querySelector("legend")?.textContent).toContain(prompt);
    expect(container.querySelector(".approval-note")).toBeNull();
  });
});
