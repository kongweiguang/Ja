// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  Composer,
  ComposerContext,
  type ComposerProps,
  type ComposerSubmit,
} from "@/features/conversation/ui/composer/Composer";

const PREFERENCES = {
  providerId: "provider_anthropic",
  modelId: "model_sonnet",
  reasoningLevel: "medium" as const,
  accessMode: "approval_required" as const,
  titleSource: "manual" as const,
};

const MODELS = [
  {
    value: "anthropic-sonnet",
    providerId: "provider_anthropic",
    providerLabel: "Anthropic",
    modelId: "model_sonnet",
    modelIdentifier: "claude-sonnet-4-5",
    modelLabel: "Claude Sonnet",
    contextWindowTokens: 200_000,
    reasoningLevelMap: { low: "low", medium: "medium", high: "high" } as const,
    defaultReasoningLevel: "medium" as const,
  },
  {
    value: "openai-gpt",
    providerId: "provider_openai",
    providerLabel: "Local 60842 - OpenAI Responses",
    modelId: "model_gpt",
    modelIdentifier: "gpt-5.6-sol",
    modelLabel: "Local 60842 - OpenAI Responses",
    contextWindowTokens: 256_000,
    reasoningLevelMap: {} as const,
    defaultReasoningLevel: null,
  },
];

interface ControlledComposerHarnessProps extends Omit<ComposerProps, "text" | "onTextChange"> {
  initialText?: string;
}

/** 模拟真实会话层持有草稿，测试不在 Composer 内重新引入第二份 state。 */
function ControlledComposerHarness({
  initialText = "",
  ...composerProps
}: ControlledComposerHarnessProps): ReactElement {
  const [text, setText] = useState(initialText);
  return <Composer {...composerProps} text={text} onTextChange={setText} />;
}

/** Radix Select 通过 trigger 与 portal option 操作，避免回退到原生 select 测试语义。 */
async function chooseOption(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
  option: string,
) {
  await user.click(screen.getByRole("combobox", { name: label }));
  await user.click(await screen.findByRole("option", { name: option }));
}

/** 模型位于一级菜单，测试一次点击即可看见并选择真实上游模型。 */
async function chooseModel(user: ReturnType<typeof userEvent.setup>, option: string) {
  await user.click(screen.getByRole("button", { name: /当前模型/ }));
  await user.click(await screen.findByRole("menuitemradio", { name: option }));
}

/** 推理强度保留按需子菜单，验证键盘/焦点语义而非旧 combobox 结构。 */
async function chooseReasoning(user: ReturnType<typeof userEvent.setup>, option: string) {
  await user.click(screen.getByRole("button", { name: /当前模型/ }));
  const submenu = await screen.findByRole("menuitem", { name: /^推理强度/ });
  submenu.focus();
  await user.keyboard("{ArrowRight}");
  await user.click(await screen.findByRole("menuitemradio", { name: option }));
}

describe("Composer", () => {
  afterEach(() => cleanup());

  it("按 Provider 分组选择模型并只提交文本与受管附件 identity", async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(request: ComposerSubmit) => void>();
    const modelChange = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        attachments={[{ attachmentId: "att_1", fileName: "设计稿.pdf", sizeBytes: 2048 }]}
        onModelChange={modelChange}
        onSend={submit}
      />,
    );

    await user.click(screen.getByRole("button", { name: /当前模型/ }));
    expect(await screen.findByText("Anthropic")).toBeVisible();
    expect(screen.getByText("Local 60842 - OpenAI Responses")).toBeVisible();
    expect(
      screen.getByRole("menuitemradio", { name: "claude-sonnet-4-5 Claude Sonnet" }),
    ).toBeVisible();
    expect(screen.getByRole("menuitemradio", { name: "gpt-5.6-sol" })).toBeVisible();
    expect(screen.getAllByText("Local 60842 - OpenAI Responses")).toHaveLength(1);
    await user.click(screen.getByRole("menuitemradio", { name: "gpt-5.6-sol" }));
    expect(modelChange).toHaveBeenCalledWith("openai-gpt");
    await user.type(screen.getByRole("textbox", { name: "消息" }), "检查测试");
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(submit).toHaveBeenCalledWith({ text: "检查测试", attachmentIds: ["att_1"] });
  });

  it("仅模型声明 reasoning 时显示思考档位，并允许 active Turn 修改下一轮模型", async () => {
    const user = userEvent.setup();
    const reasoningChange = vi.fn();
    const modelChange = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        activeTurn
        onModelChange={modelChange}
        onReasoningChange={reasoningChange}
        onSend={vi.fn()}
        onQueue={vi.fn()}
      />,
    );

    expect(screen.getByRole("button", { name: /claude-sonnet-4-5/ })).toBeEnabled();
    await chooseReasoning(user, "高");
    expect(reasoningChange).toHaveBeenCalledWith("high");
    await chooseModel(user, "gpt-5.6-sol");
    expect(modelChange).toHaveBeenCalledWith("openai-gpt");
  });

  it("无 reasoning 模型隐藏推理强度并可原子恢复默认设置", async () => {
    const user = userEvent.setup();
    const restoreDefaults = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={{
          ...PREFERENCES,
          providerId: "provider_openai",
          modelId: "model_gpt",
          reasoningLevel: null,
        }}
        models={MODELS}
        onRestoreDefaults={restoreDefaults}
        onSend={vi.fn()}
      />,
    );

    expect(screen.getByRole("button", { name: /当前模型：gpt-5.6-sol/ })).toBeVisible();
    await user.click(screen.getByRole("button", { name: /当前模型：gpt-5.6-sol/ }));
    expect(screen.queryByRole("menuitem", { name: /^推理强度/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("menuitem", { name: "恢复默认设置" }));
    expect(restoreDefaults).toHaveBeenCalledOnce();
  });

  it("支持键盘选择一级模型项，并在 Escape 后归还触发器焦点", async () => {
    const user = userEvent.setup();
    const modelChange = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        onModelChange={modelChange}
        onSend={vi.fn()}
      />,
    );

    const trigger = screen.getByRole("button", { name: /当前模型/ });
    await user.click(trigger);
    const gpt = await screen.findByRole("menuitemradio", { name: "gpt-5.6-sol" });
    gpt.focus();
    await user.keyboard(" ");
    expect(modelChange).toHaveBeenCalledWith("openai-gpt");
    expect(trigger).toHaveFocus();

    await user.click(trigger);
    await screen.findByRole("menuitemradio", { name: "gpt-5.6-sol" });
    await user.keyboard("{Escape}");
    expect(trigger).toHaveFocus();
  });

  it("允许显式恢复为模型默认推理档位", async () => {
    const user = userEvent.setup();
    const reasoningChange = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        onReasoningChange={reasoningChange}
        onSend={vi.fn()}
      />,
    );

    await chooseReasoning(user, "跟随模型默认");
    expect(reasoningChange).toHaveBeenCalledWith(null);
  });

  it("审批模式只提供需要审批与完全访问", async () => {
    const user = userEvent.setup();
    const accessChange = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        onAccessModeChange={accessChange}
        onSend={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("combobox", { name: "访问模式" }));
    expect(screen.getAllByRole("option").map((option) => option.textContent)).toEqual([
      "需要审批",
      "完全访问",
    ]);
    await user.click(screen.getByRole("option", { name: "完全访问" }));
    expect(accessChange).toHaveBeenCalledWith("full_access");
  });

  it("显示本地项目上下文，并且只在真实分支存在时显示 Git 分支", () => {
    const { rerender } = render(
      <>
        <ComposerContext workspaceLabel="ja" gitBranch="main" />
        <ControlledComposerHarness preferences={PREFERENCES} models={MODELS} onSend={vi.fn()} />
      </>,
    );

    const context = screen.getByLabelText("当前执行上下文");
    const form = screen.getByRole("form", { name: "发送消息" });
    expect(context).toHaveTextContent("ja");
    expect(context).toHaveTextContent("本地");
    expect(context).toHaveTextContent("main");
    expect(form).not.toContainElement(context);
    expect(context.compareDocumentPosition(form) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);

    rerender(
      <>
        <ComposerContext workspaceLabel="ja" />
        <ControlledComposerHarness preferences={PREFERENCES} models={MODELS} onSend={vi.fn()} />
      </>,
    );
    expect(screen.getByLabelText("当前执行上下文")).not.toHaveTextContent("main");
  });

  it("IME composition 的 Enter 不发送，组合结束后的 Enter 才提交", () => {
    const submit = vi.fn<(request: ComposerSubmit) => void>();
    render(
      <ControlledComposerHarness
        initialText="中文"
        preferences={PREFERENCES}
        models={MODELS}
        onSend={submit}
      />,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    expect(submit).not.toHaveBeenCalled();
    fireEvent.compositionEnd(input);
    fireEvent.keyDown(input, { key: "Enter" });
    expect(submit).toHaveBeenCalledOnce();
  });

  it("附件入口与可移除 chip 只调用真实 adapter 回调", async () => {
    const user = userEvent.setup();
    const add = vi.fn();
    const remove = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        attachments={[{ attachmentId: "att_1", fileName: "说明.txt", sizeBytes: 120 }]}
        onAddAttachments={add}
        onRemoveAttachment={remove}
        onSend={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "添加附件" }));
    await user.click(screen.getByRole("button", { name: "移除附件 说明.txt" }));
    expect(add).toHaveBeenCalledOnce();
    expect(remove).toHaveBeenCalledWith("att_1");
  });

  it("活动 Turn 使用共享队列 Select，并保留 Shift+Enter 换行", async () => {
    const user = userEvent.setup();
    const queue = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        activeTurn
        onSend={vi.fn()}
        onQueue={queue}
      />,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    await user.type(input, "第一行");
    await user.keyboard("{Shift>}{Enter}{/Shift}");
    await user.type(input, "第二行");
    await chooseOption(user, "消息队列", "后续消息");
    await user.click(screen.getByRole("button", { name: "加入队列" }));
    expect(queue).toHaveBeenCalledWith("第一行\n第二行", "follow_up");
  });

  /** 无真实 Usage 时底栏不占位；存在时 hover 与键盘焦点共享同一份精确 Tooltip。 */
  it("按需显示可聚焦的上下文使用量与 Token 详情", async () => {
    const user = userEvent.setup();
    const { rerender } = render(
      <ControlledComposerHarness preferences={PREFERENCES} models={MODELS} onSend={vi.fn()} />,
    );
    expect(screen.queryByRole("progressbar", { name: "上下文使用量" })).not.toBeInTheDocument();

    rerender(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        contextUsage={{
          usedTokens: 179_000,
          limitTokens: 258_000,
          percentage: 69,
          ringPercentage: 69.37984496124031,
          tone: "neutral",
          source: "provider",
          measuredAt: "2026-08-31T00:00:00Z",
        }}
        onSend={vi.fn()}
      />,
    );
    const indicator = screen.getByRole("progressbar", { name: "上下文使用量" });
    expect(indicator).toHaveAttribute("aria-valuenow", "69");
    expect(indicator).toHaveAttribute(
      "aria-valuetext",
      "已使用 69%，179K / 258K tokens，最近模型请求",
    );
    await user.hover(indicator);
    expect(await screen.findByRole("tooltip")).toHaveTextContent("上下文69%179K / 258K tokens");
    await user.unhover(indicator);
    indicator.focus();
    expect(await screen.findByRole("tooltip")).toHaveTextContent("最近模型请求");
    expect(indicator).toHaveFocus();
  });

  /** 危险态保留 progressbar 语义并展示压缩来源，颜色只作为冗余信号。 */
  it("为接近上限的压缩后计量投影危险状态", () => {
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        contextUsage={{
          usedTokens: 195_000,
          limitTokens: 200_000,
          percentage: 98,
          ringPercentage: 97.5,
          tone: "danger",
          source: "compaction",
          measuredAt: "2026-08-31T00:00:02Z",
        }}
        onSend={vi.fn()}
      />,
    );
    const indicator = screen.getByRole("progressbar", { name: "上下文使用量" });
    expect(indicator).toHaveAttribute("data-tone", "danger");
    expect(indicator).toHaveAttribute(
      "aria-valuetext",
      "已使用 98%，195K / 200K tokens，压缩后计量",
    );
  });
});
