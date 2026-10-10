// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  Composer,
  type ComposerProps,
  type ComposerSubmit,
} from "@/features/conversation/ui/composer/Composer";
import type { ConversationUsageSummary } from "@/features/conversation/application/ports";
import type { ConversationContextReference } from "@/features/conversation/domain/userContent";

const PREFERENCES = {
  providerId: "provider_anthropic",
  modelId: "model_sonnet",
  reasoningLevel: "medium" as const,
  accessMode: "approval_required" as const,
  collaborationMode: "default" as const,
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

const USAGE_SUMMARY: ConversationUsageSummary = {
  threadId: "thr_usage",
  snapshotRevision: 6,
  requestCount: 3,
  measuredRequestCount: 3,
  newInputRequestCount: 3,
  newInputTokens: 12_400,
  outputRequestCount: 3,
  outputTokens: 2_100,
  totalRequestCount: 3,
  totalTokens: 21_600,
  cacheReadRequestCount: 2,
  cacheReadTokens: 6_200,
  cacheWriteRequestCount: 1,
  cacheWriteTokens: 900,
  cacheCompleteRequestCount: 2,
  cacheCompleteInputTokens: 19_500,
  cacheCompleteReadTokens: 6_200,
};

interface ControlledComposerHarnessProps extends Omit<ComposerProps, "text" | "onTextChange"> {
  initialText?: string;
  initialContextReferences?: readonly ConversationContextReference[];
  onDraftCommit?: (text: string, commit: () => void) => void;
}

/** 模拟会话层持有草稿，并允许测试推迟 owner commit 以验证受控值时序边界。 */
function ControlledComposerHarness({
  initialText = "",
  initialContextReferences = [],
  onDraftCommit,
  ...composerProps
}: ControlledComposerHarnessProps): ReactElement {
  const [text, setText] = useState(initialText);
  const [contextReferences, setContextReferences] = useState(initialContextReferences);
  return (
    <Composer
      {...composerProps}
      text={text}
      onTextChange={(nextText) => {
        /** 保留可延后的 owner commit 闭包，供时序回归显式控制受控文本提交。 */
        const commit = () => setText(nextText);
        if (onDraftCommit === undefined) commit();
        else onDraftCommit(nextText, commit);
      }}
      contextReferences={composerProps.contextReferences ?? contextReferences}
      onContextReferencesChange={composerProps.onContextReferencesChange ?? setContextReferences}
    />
  );
}

interface ActiveQueueComposerHarnessProps
  extends Omit<ControlledComposerHarnessProps, "activeTurn" | "onEnqueue"> {
  onEnqueue: NonNullable<ComposerProps["onEnqueue"]>;
}

/** 模拟 controller 在入队意图受理时立即清空草稿，使主按钮无等待地恢复为停止动作。 */
function ActiveQueueComposerHarness({
  onEnqueue,
  ...composerProps
}: ActiveQueueComposerHarnessProps): ReactElement {
  const [text, setText] = useState("");
  return (
    <Composer
      {...composerProps}
      activeTurn
      text={text}
      onTextChange={setText}
      onEnqueue={(message) => {
        setText("");
        return onEnqueue(message);
      }}
    />
  );
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

  /** disabled 期间保留一次性请求，ready 使 textarea 可用后才执行并消费。@author kongweiguang */
  it("keeps a focus token pending until the disabled Composer becomes ready", () => {
    const { rerender } = render(
      <ControlledComposerHarness disabled focusRequest={undefined} onSend={vi.fn()} />,
    );
    const input = screen.getByRole("textbox", { name: "消息" }) as HTMLTextAreaElement;
    expect(input).toBeDisabled();

    rerender(<ControlledComposerHarness disabled focusRequest={1} onSend={vi.fn()} />);
    expect(input).toBeDisabled();
    expect(input).not.toHaveFocus();

    rerender(<ControlledComposerHarness focusRequest={1} onSend={vi.fn()} />);
    expect(input).toBeEnabled();
    expect(input).toHaveFocus();
  });

  /** 隐藏 Host 收到 token 时不消费，恢复 active 后必须完成创建侧聊的焦点交接。@author kongweiguang */
  it("waits for an inactive Composer to become active before consuming its focus token", () => {
    const { rerender } = render(
      <ControlledComposerHarness active={false} focusRequest={undefined} onSend={vi.fn()} />,
    );
    const input = screen.getByRole("textbox", { name: "消息" });

    rerender(<ControlledComposerHarness active={false} focusRequest={1} onSend={vi.fn()} />);
    expect(input).not.toHaveFocus();

    rerender(<ControlledComposerHarness active focusRequest={1} onSend={vi.fn()} />);
    expect(input).toHaveFocus();
  });

  /** 只接受新的 owner token，保持初始快照不抢焦点并允许后续显式请求聚焦。@author kongweiguang */
  it("focuses only when the owner publishes a new focus token", () => {
    const { rerender } = render(
      <>
        <button type="button">其他操作</button>
        <ControlledComposerHarness focusRequest={1} onSend={vi.fn()} />
      </>,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    const otherAction = screen.getByRole("button", { name: "其他操作" });
    otherAction.focus();
    expect(input).not.toHaveFocus();

    rerender(
      <>
        <button type="button">其他操作</button>
        <ControlledComposerHarness focusRequest={2} onSend={vi.fn()} />
      </>,
    );
    expect(input).toHaveFocus();
  });

  /** 重放相同 token 或切换 active 不得二次聚焦，避免夺走用户随后选择的控件焦点。@author kongweiguang */
  it("does not let a duplicate focus token steal focus again", () => {
    const { rerender } = render(
      <>
        <button type="button">其他操作</button>
        <ControlledComposerHarness focusRequest={undefined} onSend={vi.fn()} />
      </>,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    const otherAction = screen.getByRole("button", { name: "其他操作" });

    rerender(
      <>
        <button type="button">其他操作</button>
        <ControlledComposerHarness focusRequest={1} onSend={vi.fn()} />
      </>,
    );
    expect(input).toHaveFocus();

    otherAction.focus();
    rerender(
      <>
        <button type="button">其他操作</button>
        <ControlledComposerHarness active={false} focusRequest={1} onSend={vi.fn()} />
      </>,
    );
    rerender(
      <>
        <button type="button">其他操作</button>
        <ControlledComposerHarness active focusRequest={1} onSend={vi.fn()} />
      </>,
    );
    expect(otherAction).toHaveFocus();
  });

  /** focus() 未能改变 activeElement 时保留 token，后续 active 边界可重试而不引入轮询。@author kongweiguang */
  it("keeps a focus token pending until the textarea actually receives focus", () => {
    const { rerender } = render(
      <ControlledComposerHarness focusRequest={undefined} onSend={vi.fn()} />,
    );
    const input = screen.getByRole("textbox", { name: "消息" }) as HTMLTextAreaElement;
    vi.spyOn(input, "focus").mockImplementationOnce(() => {});

    rerender(<ControlledComposerHarness focusRequest={1} onSend={vi.fn()} />);
    expect(input).not.toHaveFocus();

    rerender(<ControlledComposerHarness active={false} focusRequest={1} onSend={vi.fn()} />);
    rerender(<ControlledComposerHarness active focusRequest={1} onSend={vi.fn()} />);
    expect(input).toHaveFocus();
  });

  it("按 interactionPresentation 在完整与紧凑补充输入之间切换，不遗失同轨提问面板", () => {
    const { rerender } = render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        interactionSlot={<section aria-label="待回答的问题">问题面板</section>}
        interactionPresentation="none"
        onSend={vi.fn()}
      />,
    );

    expect(screen.getByRole("textbox", { name: "消息" })).toBeVisible();
    expect(screen.getByRole("combobox", { name: "访问模式" })).toBeVisible();
    expect(screen.getByRole("button", { name: /当前模型/ })).toBeVisible();
    expect(screen.getByRole("region", { name: "待回答的问题" })).toBeVisible();

    rerender(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        interactionSlot={<section aria-label="待回答的问题">问题面板</section>}
        interactionPresentation="expanded"
        onSend={vi.fn()}
      />,
    );
    expect(screen.getByRole("textbox", { name: "补充要求" })).toHaveAttribute(
      "placeholder",
      "都不合适？补充你的要求",
    );
    expect(screen.queryByRole("combobox", { name: "访问模式" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /当前模型/ })).not.toBeInTheDocument();
    expect(screen.getByRole("form", { name: "发送消息" })).toHaveAttribute(
      "data-interaction-presentation",
      "expanded",
    );

    rerender(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        interactionPresentation="collapsed"
        onSend={vi.fn()}
      />,
    );
    expect(screen.getByRole("textbox", { name: "消息" })).toBeVisible();
    expect(screen.getByRole("combobox", { name: "访问模式" })).toBeVisible();
    expect(screen.getByRole("button", { name: /当前模型/ })).toBeVisible();
    expect(screen.getByRole("form", { name: "发送消息" })).toHaveAttribute(
      "data-interaction-presentation",
      "collapsed",
    );
  });

  /** 无模型时不接纳无法执行的发送；模型设置入口保留原草稿并可直接跳转。 */
  it("keeps the draft and opens model settings when no model is configured", async () => {
    const user = userEvent.setup();
    const onOpenModelSettings = vi.fn();
    const onSend = vi.fn();
    render(
      <ControlledComposerHarness
        initialText="保留这段草稿"
        preferences={PREFERENCES}
        models={[]}
        onOpenModelSettings={onOpenModelSettings}
        onSend={onSend}
      />,
    );

    expect(screen.getByRole("button", { name: "发送" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "未配置模型，打开模型设置" }));

    expect(onOpenModelSettings).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("textbox", { name: "消息" })).toHaveValue("保留这段草稿");
    expect(onSend).not.toHaveBeenCalled();
  });

  /** 上游或凭据准入失败时把草稿留在输入框，并提供明确的模型设置操作。 */
  it("offers model settings after a recoverable model admission failure", async () => {
    const user = userEvent.setup();
    const onOpenModelSettings = vi.fn();
    render(
      <ControlledComposerHarness
        initialText="连接故障后仍保留"
        preferences={PREFERENCES}
        models={MODELS}
        error="模型连接暂不可用，输入草稿已保留。请检查连接和凭据。"
        modelSettingsRequired
        onOpenModelSettings={onOpenModelSettings}
        onSend={vi.fn()}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("输入草稿已保留");
    await user.click(screen.getByRole("button", { name: "打开模型设置" }));
    expect(onOpenModelSettings).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("textbox", { name: "消息" })).toHaveValue("连接故障后仍保留");
  });

  it("展开提问时保留附件与取消能力，并将补充内容只入队到当前中断 Turn", async () => {
    const user = userEvent.setup();
    const enqueue = vi.fn();
    const send = vi.fn();
    const addAttachments = vi.fn();
    const cancel = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        initialText="请直接给出适配方案"
        suspendedTurn
        interactionPresentation="expanded"
        onSend={send}
        onEnqueue={enqueue}
        onAddAttachments={addAttachments}
        onCancel={cancel}
      />,
    );

    expect(screen.getByRole("textbox", { name: "补充要求" })).toHaveValue("请直接给出适配方案");
    await user.click(screen.getByRole("button", { name: "添加附件" }));
    await user.click(screen.getByRole("button", { name: "取消运行" }));
    await user.click(screen.getByRole("button", { name: "发送补充" }));
    expect(addAttachments).toHaveBeenCalledOnce();
    expect(cancel).toHaveBeenCalledOnce();
    expect(enqueue).toHaveBeenCalledWith({
      text: "请直接给出适配方案",
      attachmentIds: [],
      contextReferences: [],
    });
    expect(send).not.toHaveBeenCalled();
  });

  /** 原始草稿身份由 controller 比对后清空；UI 提前 trim 会让尾换行草稿永远留在输入框。 */
  it.each(["enter", "modifier-enter"] as const)(
    "%s 发送保留原始草稿供精确清空",
    async (sendShortcut) => {
      const user = userEvent.setup();
      const onSend = vi.fn();
      render(
        <ControlledComposerHarness
          preferences={PREFERENCES}
          models={MODELS}
          initialText={"  保留原始草稿\n"}
          sendShortcut={sendShortcut}
          onSend={onSend}
        />,
      );
      const input = screen.getByRole("textbox", { name: "消息" });
      await user.click(input);
      await user.keyboard(sendShortcut === "enter" ? "{Enter}" : "{Control>}{Enter}{/Control}");
      expect(onSend).toHaveBeenCalledWith({
        text: "  保留原始草稿\n",
        attachmentIds: [],
        contextReferences: [],
      });
    },
  );

  it("按 Provider 分组选择模型并只提交文本与受管附件 identity", async () => {
    const user = userEvent.setup();
    const submit = vi.fn<(request: ComposerSubmit) => void>();
    const modelChange = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        attachments={[
          {
            attachmentId: "att_1",
            fileName: "设计稿.pdf",
            sizeBytes: 2048,
            mediaKind: "pdf",
          },
        ]}
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
    expect(submit).toHaveBeenCalledWith({
      text: "检查测试",
      attachmentIds: ["att_1"],
      contextReferences: [],
    });
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
        onEnqueue={vi.fn()}
      />,
    );

    expect(screen.getByRole("button", { name: /claude-sonnet-4-5/ })).toBeEnabled();
    await chooseReasoning(user, "高 (high)");
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

  it("将计划入口固定在访问权限右侧", () => {
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        modeStatus={<button type="button">计划</button>}
        onSend={vi.fn()}
      />,
    );

    const accessMode = screen.getByRole("combobox", { name: "访问模式" });
    const planEntry = screen.getByRole("button", { name: "计划" });
    expect(
      accessMode.compareDocumentPosition(planEntry) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
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

  it("modifier-enter 模式保留 Enter 换行，并让 Ctrl+Enter 发送普通与排队消息", () => {
    const submit = vi.fn<(request: ComposerSubmit) => void>();
    render(
      <ControlledComposerHarness
        initialText="第一行"
        sendShortcut="modifier-enter"
        preferences={PREFERENCES}
        models={MODELS}
        onSend={submit}
      />,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(submit).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: "Enter", ctrlKey: true });
    expect(submit).toHaveBeenCalledOnce();
  });

  it("IME composition 期间关闭建议且不执行候选", () => {
    const execute = vi.fn();
    render(
      <ControlledComposerHarness
        initialText="/settings"
        slashCommands={[
          {
            id: "settings",
            name: "settings",
            aliases: ["设置"],
            label: "设置",
            description: "打开设置",
            available: true,
            execute,
          },
        ]}
        onSend={vi.fn()}
      />,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    expect(screen.getByRole("listbox", { name: "指令" })).toBeVisible();
    fireEvent.compositionStart(input);
    expect(screen.queryByRole("listbox", { name: "指令" })).not.toBeInTheDocument();
    fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    expect(execute).not.toHaveBeenCalled();
    fireEvent.compositionEnd(input);
    expect(screen.getByRole("listbox", { name: "指令" })).toBeVisible();
  });

  it("附件入口与可移除 chip 只调用真实 adapter 回调", async () => {
    const user = userEvent.setup();
    const add = vi.fn();
    const remove = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        attachments={[
          {
            attachmentId: "att_1",
            fileName: "说明.txt",
            sizeBytes: 120,
            mediaKind: "text",
          },
        ]}
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

  /** 未决状态必须逐项可恢复，且任何 importing/failed/removing 都阻止静默漏发。 */
  it("展示逐项进度与失败恢复，并在未决附件存在时禁止发送", async () => {
    const user = userEvent.setup();
    const submit = vi.fn();
    const retry = vi.fn();
    const remove = vi.fn();
    render(
      <ControlledComposerHarness
        initialText="请分析"
        preferences={PREFERENCES}
        models={MODELS}
        attachmentDraftItems={[
          {
            state: "importing",
            operationId: "op_copy",
            attemptId: "attempt_copy",
            itemId: "item_copy",
            fileName: "很长的设计说明文档.txt",
            sizeBytes: 100,
            mediaKind: "text",
            phase: "copying",
            bytesCopied: 40,
            totalBytes: 100,
          },
          {
            state: "failed",
            operationId: "op_failed",
            attemptId: "attempt_failed",
            itemId: "item_failed",
            fileName: "截图.png",
            mediaKind: "image",
            code: "ATTACHMENT_RUNTIME_FAILED",
            message: "附件服务暂不可用",
            retryable: true,
          },
        ]}
        onRetryAttachment={retry}
        onRemoveAttachment={remove}
        onSend={submit}
      />,
    );

    expect(
      screen.getByRole("progressbar", { name: "导入附件 很长的设计说明文档.txt" }),
    ).toHaveAttribute("aria-valuenow", "40");
    expect(screen.getByText("附件服务暂不可用").closest("li")).toHaveAttribute(
      "data-error-code",
      "ATTACHMENT_RUNTIME_FAILED",
    );
    expect(screen.getByRole("button", { name: "发送" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "重试" }));
    await user.click(screen.getByRole("button", { name: "取消附件 很长的设计说明文档.txt" }));
    expect(retry).toHaveBeenCalledWith("item_failed");
    expect(remove).toHaveBeenCalledWith("item_copy");
    expect(submit).not.toHaveBeenCalled();
  });

  /** 图片和文本是直接对象操作，二进制保持静态文件块且 X 始终是独立按钮。 */
  it("只为图片和文本附件提供右栏预览意图", async () => {
    const user = userEvent.setup();
    const openPreview = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        attachmentDraftItems={[
          {
            state: "ready",
            itemId: "item_image",
            attachmentId: "att_image",
            fileName: "示意图.png",
            sizeBytes: 256,
            mediaKind: "image",
            mediaType: "image/png",
            thumbnailUrl: "ja-attachment://thumb_image",
          },
          {
            state: "ready",
            itemId: "item_text",
            attachmentId: "att_text",
            fileName: "说明.txt",
            sizeBytes: 128,
            mediaKind: "text",
            mediaType: "text/plain",
          },
          {
            state: "ready",
            itemId: "item_image_without_thumbnail",
            attachmentId: "att_image_without_thumbnail",
            fileName: "尚未生成缩略图.png",
            sizeBytes: 384,
            mediaKind: "image",
            mediaType: "image/png",
          },
          {
            state: "ready",
            itemId: "item_binary",
            attachmentId: "att_binary",
            fileName: "归档.zip",
            sizeBytes: 512,
            mediaKind: "binary",
          },
        ]}
        onOpenAttachmentPreview={openPreview}
        onRemoveAttachment={vi.fn()}
        onSend={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "预览附件 示意图.png" }));
    await user.click(screen.getByRole("button", { name: "预览附件 说明.txt" }));
    await user.click(screen.getByRole("button", { name: "预览附件 尚未生成缩略图.png" }));
    expect(openPreview).toHaveBeenCalledTimes(3);
    expect(screen.queryByRole("button", { name: "预览附件 归档.zip" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "移除附件 归档.zip" })).toBeVisible();
    expect(
      screen
        .getByRole("list", { name: "待发送附件" })
        .querySelector<HTMLImageElement>('li[data-media-kind="image"] img'),
    ).toHaveAttribute("src", "ja-attachment://thumb_image");
    expect(screen.getByText("尚未生成缩略图").closest("li")).not.toHaveAttribute(
      "data-has-thumbnail",
    );
    expect(
      screen.getByRole("list", { name: "待发送附件" }).querySelector('li[data-media-kind="image"]'),
    ).toHaveAttribute("data-has-thumbnail", "true");
  });

  /** 直传缩略图已由 Host 签发平台 origin；真实读取失败直接显示可访问降级态。 */
  it("直传图片 Windows origin 失败后直接降级", async () => {
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        attachmentDraftItems={[
          {
            state: "ready",
            itemId: "item_direct_image",
            attachmentId: "att_direct_image",
            fileName: "直传图片.png",
            sizeBytes: 256,
            mediaKind: "image",
            mediaType: "image/png",
            thumbnailUrl: "http://ja-attachment.localhost/thumbnail/direct-image",
          },
        ]}
        onOpenAttachmentPreview={vi.fn()}
        onRemoveAttachment={vi.fn()}
        onSend={vi.fn()}
      />,
    );

    const first = screen
      .getByRole("list", { name: "待发送附件" })
      .querySelector<HTMLElement>('li[data-media-kind="image"] .ja-composer-attachment__visual');
    expect(first).not.toBeNull();
    const image = first!.querySelector<HTMLImageElement>("img");
    expect(image).not.toBeNull();
    expect(image).toHaveAttribute("src", "http://ja-attachment.localhost/thumbnail/direct-image");
    fireEvent.error(image!);

    expect(first!.querySelector('[data-state="unavailable"]')).toHaveAccessibleName(
      "直传图片.png 缩略图不可用",
    );
  });

  /** 非空纯文本保持 WebView 原生行为；无文本时由原生边界判断文件或位图。 */
  it("纯文本原生粘贴且无文本时调用原生剪贴板", () => {
    const pasteImage = vi.fn();
    render(
      <ControlledComposerHarness
        activeTurn
        preferences={PREFERENCES}
        models={MODELS}
        onPasteAttachments={pasteImage}
        onSend={vi.fn()}
      />,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    const textPaste = new Event("paste", { bubbles: true, cancelable: true });
    Object.defineProperty(textPaste, "clipboardData", {
      value: { getData: () => "正文", items: [{ kind: "string", type: "text/plain" }] },
    });
    input.dispatchEvent(textPaste);
    expect(textPaste.defaultPrevented).toBe(false);
    expect(pasteImage).not.toHaveBeenCalled();

    const imagePaste = new Event("paste", { bubbles: true, cancelable: true });
    Object.defineProperty(imagePaste, "clipboardData", {
      value: { getData: () => "", items: [] },
    });
    input.dispatchEvent(imagePaste);
    expect(imagePaste.defaultPrevented).toBe(true);
    expect(pasteImage).toHaveBeenCalledOnce();
  });

  /** 应用级路由器只向 Composer 投递已命中事件；组件消费 token 后立即清理视觉态。 */
  it("消费应用级路由器分发的原生 drop token", () => {
    const drop = vi.fn();
    const { rerender } = render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        nativeDropEvent={{ phase: "enter", x: 30, y: 30, count: 1 }}
        onDropAttachments={drop}
        onSend={vi.fn()}
      />,
    );
    const form = screen.getByRole("form", { name: "发送消息" });
    rerender(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        nativeDropEvent={{ phase: "over", x: 30, y: 30, count: 1 }}
        onDropAttachments={drop}
        onSend={vi.fn()}
      />,
    );
    expect(form).toHaveAttribute("data-drop-active", "true");
    rerender(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        nativeDropEvent={{
          phase: "drop",
          x: 30,
          y: 30,
          count: 1,
          dropToken: "drop_token",
        }}
        onDropAttachments={drop}
        onSend={vi.fn()}
      />,
    );
    expect(drop).toHaveBeenCalledWith("drop_token");
    expect(form).not.toHaveAttribute("data-drop-active");
    rerender(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        disabled
        nativeDropEvent={{
          phase: "drop",
          x: 30,
          y: 30,
          count: 1,
          dropToken: "drop_token",
        }}
        onDropAttachments={drop}
        onSend={vi.fn()}
      />,
    );
    rerender(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        nativeDropEvent={{
          phase: "drop",
          x: 30,
          y: 30,
          count: 1,
          dropToken: "drop_token",
        }}
        onDropAttachments={drop}
        onSend={vi.fn()}
      />,
    );
    expect(drop).toHaveBeenCalledOnce();
  });

  /** @ 只搜索路径并形成可移除引用，Tab 也属于合法 token 边界，重复选择按稳定身份去重。 */
  it("在任意空白边界选择 Workspace 路径并维护去重 Chip", async () => {
    const user = userEvent.setup();
    const searchPaths = vi.fn(async (query: string) => ({
      threadId: "thr_one",
      workspaceId: "ws_one",
      generation: 3,
      query,
      items: [{ relativePath: "src/main.rs", kind: "file" as const }],
      truncated: false,
    }));
    render(
      <ControlledComposerHarness
        initialText={"说明\t@src"}
        threadId="thr_one"
        workspaceId="ws_one"
        runtimeGeneration={3}
        onSearchWorkspacePaths={searchPaths}
        onSend={vi.fn()}
      />,
    );

    const option = await screen.findByRole("option", { name: /main\.rs/ });
    expect(searchPaths).toHaveBeenCalledWith("src");
    await user.click(option);
    const input = screen.getByRole("textbox", { name: "消息" });
    expect(input).toHaveValue("说明\t");
    await waitFor(() => expect(input).toHaveFocus());
    const removeReference = screen.getByRole("button", { name: "移除上下文 main.rs" });
    expect(removeReference).toBeVisible();
    expect(removeReference.closest("li")).toHaveAttribute("data-reference-type", "workspace");
    expect(removeReference.closest("li")).toHaveAttribute("data-reference-kind", "file");
    expect(removeReference.closest("li")).toHaveTextContent("main.rs");

    await user.type(input, "@src");
    await user.click(await screen.findByRole("option", { name: /main\.rs/ }));
    expect(screen.getAllByRole("button", { name: "移除上下文 main.rs" })).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "移除上下文 main.rs" }));
    expect(screen.queryByText("src/main.rs")).not.toBeInTheDocument();
  });

  /** 隐藏期间无路径搜索；恢复只查当前 Thread 一次，身份不匹配的旧响应不能回填。 */
  it("cancels hidden Workspace search and fences a stale scoped response", async () => {
    type SearchResult = Awaited<ReturnType<NonNullable<ComposerProps["onSearchWorkspacePaths"]>>>;
    let resolveStale: ((result: SearchResult) => void) | undefined;
    const staleResult = new Promise<SearchResult>((resolve) => {
      resolveStale = resolve;
    });
    const searchPaths = vi.fn(
      (query: string): Promise<SearchResult> =>
        searchPaths.mock.calls.length === 1
          ? staleResult
          : Promise.resolve({
              threadId: "thr_one",
              workspaceId: "ws_one",
              generation: 3,
              query,
              items: [{ relativePath: "current.ts", kind: "file" }],
              truncated: false,
            }),
    );
    const { rerender } = render(
      <ControlledComposerHarness
        active={false}
        initialText="@src"
        threadId="thr_one"
        workspaceId="ws_one"
        runtimeGeneration={3}
        onSearchWorkspacePaths={searchPaths}
        onSend={vi.fn()}
      />,
    );
    await act(async () => new Promise((resolve) => setTimeout(resolve, 150)));
    expect(searchPaths).not.toHaveBeenCalled();

    rerender(
      <ControlledComposerHarness
        active
        initialText="@src"
        threadId="thr_one"
        workspaceId="ws_one"
        runtimeGeneration={3}
        onSearchWorkspacePaths={searchPaths}
        onSend={vi.fn()}
      />,
    );
    await waitFor(() => expect(searchPaths).toHaveBeenCalledTimes(1));

    rerender(
      <ControlledComposerHarness
        active={false}
        initialText="@src"
        threadId="thr_one"
        workspaceId="ws_one"
        runtimeGeneration={3}
        onSearchWorkspacePaths={searchPaths}
        onSend={vi.fn()}
      />,
    );
    await act(async () => {
      resolveStale?.({
        threadId: "thr_stale",
        workspaceId: "ws_stale",
        generation: 2,
        query: "src",
        items: [{ relativePath: "stale.ts", kind: "file" }],
        truncated: false,
      });
      await staleResult;
    });
    expect(screen.queryByRole("option", { name: /stale\.ts/u })).not.toBeInTheDocument();
    expect(searchPaths).toHaveBeenCalledTimes(1);

    rerender(
      <ControlledComposerHarness
        active
        initialText="@src"
        threadId="thr_one"
        workspaceId="ws_one"
        runtimeGeneration={3}
        onSearchWorkspacePaths={searchPaths}
        onSend={vi.fn()}
      />,
    );
    expect(await screen.findByRole("option", { name: /current\.ts/u })).toBeVisible();
    expect(searchPaths).toHaveBeenCalledTimes(2);
  });

  /** Workspace 对象只有在组合层提供真实 Files 处理器时才可点击，移除仍保持独立动作。 */
  it("把 Workspace 文件卡点击交给 Files 预览处理器", async () => {
    const user = userEvent.setup();
    const openReference = vi.fn();
    render(
      <ControlledComposerHarness
        initialContextReferences={[
          {
            type: "workspace_reference",
            workspaceId: "ws_one",
            relativePath: "src/main.rs",
            kind: "file",
          },
        ]}
        onOpenWorkspaceReference={openReference}
        onSend={vi.fn()}
      />,
    );
    const open = screen.getByRole("button", { name: "在文件中预览 main.rs" });
    await user.click(open);
    expect(openReference).toHaveBeenCalledWith(
      expect.objectContaining({ relativePath: "src/main.rs" }),
      open,
    );
    expect(screen.getByRole("button", { name: "移除上下文 main.rs" })).toBeVisible();
  });

  /** 没有真实打开回调时 Workspace 卡保持静态内容，避免渲染无法兑现的按钮。 */
  it("没有 Files 处理器时不渲染 Workspace 假预览按钮", () => {
    render(
      <ControlledComposerHarness
        initialContextReferences={[
          {
            type: "workspace_reference",
            workspaceId: "ws_one",
            relativePath: "src/main.rs",
            kind: "file",
          },
        ]}
        onSend={vi.fn()}
      />,
    );
    expect(screen.queryByRole("button", { name: "在文件中预览 main.rs" })).not.toBeInTheDocument();
    expect(screen.getByText("main")).toBeVisible();
  });

  /** 异步路径结果提交后，active option 必须先稳定再接收按键，避免迟到绘制帧覆盖用户选择。 */
  it("在 Workspace 建议中往返方向键后保留稳定 active descendant", async () => {
    const searchPaths = vi.fn(async (query: string) => ({
      threadId: "thr_one",
      workspaceId: "ws_one",
      generation: 3,
      query,
      items: [
        { relativePath: "sample.ts", kind: "file" as const },
        { relativePath: "sample.test.ts", kind: "file" as const },
      ],
      truncated: false,
    }));
    render(
      <ControlledComposerHarness
        initialText="@sample"
        threadId="thr_one"
        workspaceId="ws_one"
        runtimeGeneration={3}
        onSearchWorkspacePaths={searchPaths}
        onSend={vi.fn()}
      />,
    );

    const input = screen.getByRole("textbox", { name: "消息" });
    input.focus();
    const first = await screen.findByRole("option", { name: /sample\.ts/u });
    const second = screen.getByRole("option", { name: /sample\.test\.ts/u });
    await waitFor(() => expect(first).toHaveAttribute("aria-selected", "true"));
    fireEvent.keyDown(input, { key: "ArrowDown" });
    await waitFor(() => expect(second).toHaveAttribute("aria-selected", "true"));
    fireEvent.keyDown(input, { key: "ArrowUp" });
    await waitFor(() => expect(first).toHaveAttribute("aria-selected", "true"));
  });

  it("在面板内完成路径加载、错误、重试与空结果闭环", async () => {
    const user = userEvent.setup();
    const searchPaths = vi
      .fn()
      .mockRejectedValueOnce(new Error("private search failure"))
      .mockResolvedValueOnce({
        threadId: "thr_one",
        workspaceId: "ws_one",
        generation: 3,
        query: "missing",
        items: [],
        truncated: false,
      });
    render(
      <ControlledComposerHarness
        initialText="@missing"
        threadId="thr_one"
        workspaceId="ws_one"
        runtimeGeneration={3}
        onSearchWorkspacePaths={searchPaths}
        onSend={vi.fn()}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("正在加载建议");
    expect(await screen.findByRole("alert")).toHaveTextContent("文件与目录暂时不可用");
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("没有匹配的文件或目录")).toBeVisible();
    expect(searchPaths).toHaveBeenCalledTimes(2);
  });

  it("不会在邮箱、金额或非首 token 的 Slash 中误开建议", () => {
    const { rerender } = render(
      <ControlledComposerHarness initialText="mail@example.com" onSend={vi.fn()} />,
    );
    expect(screen.queryByLabelText("输入建议")).not.toBeInTheDocument();
    rerender(<ControlledComposerHarness key="cost" initialText="cost$skill" onSend={vi.fn()} />);
    expect(screen.queryByLabelText("输入建议")).not.toBeInTheDocument();
    rerender(
      <ControlledComposerHarness key="slash" initialText="正文 /settings" onSend={vi.fn()} />,
    );
    expect(screen.queryByLabelText("输入建议")).not.toBeInTheDocument();
  });

  /** Skill 只修饰当前消息，不能在没有正文、附件或 Workspace 引用时制造空用户输入。 */
  it("单独选择 Skill 时保持不可发送", async () => {
    const user = userEvent.setup();
    const submit = vi.fn();
    render(
      <ControlledComposerHarness
        initialText="$"
        skills={[
          {
            skillId: "skill_ui",
            name: "Apple UI",
            description: "界面生产验收",
            scope: "project",
          },
        ]}
        onSend={submit}
      />,
    );

    await user.click(screen.getByRole("option", { name: /Apple UI/ }));
    expect(screen.getByRole("button", { name: "发送" })).toBeDisabled();
    expect(submit).not.toHaveBeenCalled();
  });

  /** Esc 关闭的是稳定字面 token 会话，移动光标不重开，只有正文片段变化后重新识别。 */
  it("Esc 保留字面 token 并在片段变化前保持关闭", async () => {
    const user = userEvent.setup();
    render(
      <ControlledComposerHarness
        initialText="$apple"
        skills={[
          {
            skillId: "skill_ui",
            name: "Apple UI",
            description: "界面生产验收",
            scope: "project",
          },
        ]}
        onSend={vi.fn()}
      />,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    input.focus();
    expect(screen.getByRole("listbox", { name: "Skills" })).toBeVisible();
    await user.keyboard("{Escape}");
    expect(input).toHaveValue("$apple");
    expect(screen.queryByRole("listbox", { name: "Skills" })).not.toBeInTheDocument();

    (input as HTMLTextAreaElement).setSelectionRange(2, 2);
    fireEvent.select(input);
    expect(screen.queryByRole("listbox", { name: "Skills" })).not.toBeInTheDocument();
    await user.type(input, "x");
    expect(screen.getByRole("listbox", { name: "Skills" })).toBeVisible();
    await user.keyboard("{Escape}");
    await user.clear(input);
    await user.type(input, "$apple");
    expect(screen.getByRole("listbox", { name: "Skills" })).toBeVisible();
  });

  /** Slash 键盘导航跳过 disabled 项，执行只调用真实 UI action，永不提交模型消息。 */
  it("执行内置 Slash action 并跳过不可用命令", async () => {
    const user = userEvent.setup();
    const disabled = vi.fn();
    const settings = vi.fn();
    const sidebar = vi.fn();
    const submit = vi.fn();
    render(
      <ControlledComposerHarness
        initialText="/"
        slashCommands={[
          {
            id: "review",
            name: "review",
            aliases: ["changes"],
            label: "审查",
            description: "打开最近修改",
            available: false,
            unavailableReason: "没有可靠修改",
            execute: disabled,
          },
          {
            id: "settings",
            name: "settings",
            aliases: ["设置"],
            label: "设置",
            description: "打开设置",
            available: true,
            execute: settings,
          },
          {
            id: "sidebar",
            name: "sidebar",
            aliases: ["侧栏"],
            label: "切换侧栏",
            description: "切换侧栏",
            available: true,
            execute: sidebar,
          },
        ]}
        onSend={submit}
      />,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    input.focus();
    expect(screen.getByRole("option", { name: /没有可靠修改/ })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    await waitFor(() =>
      expect(screen.getByRole("option", { name: /打开设置/ })).toHaveAttribute(
        "aria-selected",
        "true",
      ),
    );
    fireEvent.keyDown(input, { key: "ArrowUp" });
    await waitFor(() =>
      expect(screen.getByRole("option", { name: /切换侧栏/ })).toHaveAttribute(
        "aria-selected",
        "true",
      ),
    );
    await user.keyboard("{Enter}");
    expect(sidebar).toHaveBeenCalledOnce();
    expect(settings).not.toHaveBeenCalled();
    expect(disabled).not.toHaveBeenCalled();
    expect(submit).not.toHaveBeenCalled();
  });

  /** 通过键盘仍可找到 Plan；移除的命令不应留下空白入口。 */
  it("在添加分组只展示 Plan 命令", async () => {
    const user = userEvent.setup();
    const plan = vi.fn();
    render(
      <ControlledComposerHarness
        initialText="/"
        slashCommands={[
          {
            id: "plan",
            name: "plan",
            aliases: ["计划"],
            group: "添加",
            icon: "plan",
            label: "计划",
            description: "先制定计划再决定是否执行",
            available: true,
            argument: { mode: "optional", label: "计划模式", placeholder: "输入 on 或 off" },
            execute: plan,
          },
        ]}
        onSend={vi.fn()}
      />,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    input.focus();
    const group = screen.getByRole("group", { name: "添加" });
    expect(within(group).getAllByRole("option")).toHaveLength(1);
    expect(
      within(group).getByRole("option", { name: /计划.*先制定计划再决定是否执行/u }),
    ).toBeVisible();
    await user.keyboard("{Escape}");
    expect(screen.getByRole("textbox", { name: "消息" })).toHaveFocus();
    expect(plan).not.toHaveBeenCalled();
  });

  /** 直接 Slash 调用只把结构化参数交给动作，命令正文不得作为普通消息发送。 */
  it("直接执行 /plan off 与必填参数命令", async () => {
    const user = userEvent.setup();
    const plan = vi.fn();
    const custom = vi.fn();
    const submit = vi.fn();
    const commands: NonNullable<ComposerProps["slashCommands"]> = [
      {
        id: "plan",
        name: "plan",
        aliases: ["计划"],
        label: "计划模式",
        description: "切换计划模式",
        available: true,
        argument: { mode: "optional", label: "计划模式", placeholder: "输入 on 或 off" },
        execute: plan,
      },
      {
        id: "custom",
        name: "custom",
        aliases: ["自定义"],
        label: "自定义命令",
        description: "测试必填参数",
        available: true,
        argument: { mode: "required", label: "内容", placeholder: "填写内容" },
        execute: custom,
      },
    ];
    const { unmount } = render(
      <ControlledComposerHarness
        initialText="/plan off"
        slashCommands={commands}
        onSend={submit}
      />,
    );
    const planInput = screen.getByRole("textbox", { name: "消息" });
    planInput.focus();
    await user.keyboard("{Enter}");
    await waitFor(() => expect(plan).toHaveBeenCalledWith({ argument: "off" }));
    expect(planInput).toHaveValue("");
    unmount();

    render(
      <ControlledComposerHarness
        initialText="/custom 完成生产验收"
        slashCommands={commands}
        onSend={submit}
      />,
    );
    const customInput = screen.getByRole("textbox", { name: "消息" });
    customInput.focus();
    await user.keyboard("{Enter}");
    await waitFor(() => expect(custom).toHaveBeenCalledWith({ argument: "完成生产验收" }));
    expect(customInput).toHaveValue("");
    expect(submit).not.toHaveBeenCalled();
  });

  /** 异步命令失败要恢复同一内联编辑态，避免用户重输必填参数。 */
  it("必填参数命令失败后恢复正文和输入焦点", async () => {
    const user = userEvent.setup();
    render(
      <ControlledComposerHarness
        initialText="/custom"
        slashCommands={[
          {
            id: "custom",
            name: "custom",
            aliases: ["自定义"],
            label: "自定义命令",
            description: "测试必填参数",
            available: true,
            argument: { mode: "required", label: "内容", placeholder: "填写内容" },
            execute: () => Promise.reject(new Error("private command failure")),
          },
        ]}
        onSend={vi.fn()}
      />,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    input.focus();
    await user.keyboard("{Enter}");
    const customInput = screen.getByRole("textbox", { name: "填写内容" });
    await user.type(customInput, "保留这段内容");
    await user.keyboard("{Enter}");

    await waitFor(() => expect(screen.getByRole("textbox", { name: "填写内容" })).toHaveFocus());
    expect(screen.getByRole("textbox", { name: "填写内容" })).toHaveValue("保留这段内容");
    expect(screen.getByRole("alert")).toHaveTextContent("指令未完成，请重试。");
  });

  /** 外层模式只改变提示语，不改变 Composer 的提交和访问策略控件结构。 */
  it("按 collaboration mode 投影调用方提供的输入提示", () => {
    render(<ControlledComposerHarness placeholder="描述需要制定计划的任务…" onSend={vi.fn()} />);
    expect(screen.getByRole("textbox", { name: "消息" })).toHaveAttribute(
      "placeholder",
      "描述需要制定计划的任务…",
    );
  });

  it("Slash action 失败时恢复完整草稿、选择位置和邻近错误", async () => {
    const user = userEvent.setup();
    render(
      <ControlledComposerHarness
        initialText="/settings 后继续"
        slashCommands={[
          {
            id: "settings",
            name: "settings",
            aliases: ["设置"],
            label: "设置",
            description: "打开设置",
            available: true,
            execute: () => Promise.reject(new Error("private command failure")),
          },
        ]}
        onSend={vi.fn()}
      />,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    input.focus();
    (input as HTMLTextAreaElement).setSelectionRange(4, 4);
    fireEvent.keyUp(input, { key: "ArrowLeft" });
    await user.click(screen.getByRole("option", { name: /\/settings/ }));
    await waitFor(() => expect(input).toHaveValue("/settings 后继续"));
    await waitFor(() => {
      expect(input).toHaveFocus();
      expect((input as HTMLTextAreaElement).selectionStart).toBe(4);
    });
    expect(screen.getByRole("alert")).toHaveTextContent("指令未完成，请重试。");
  });

  /** 同步异常也必须进入与异步失败相同的草稿/选区恢复路径，且释放忙状态供用户重试。 */
  it("Slash action 同步抛错后恢复草稿和选区，并允许重试", async () => {
    const user = userEvent.setup();
    const originalText = "/settings 后继续";
    const execute = vi
      .fn<() => void | Promise<void>>()
      .mockImplementationOnce(() => {
        throw new Error("private command failure");
      })
      .mockResolvedValue(undefined);
    render(
      <ControlledComposerHarness
        initialText={originalText}
        slashCommands={[
          {
            id: "settings",
            name: "settings",
            aliases: ["设置"],
            label: "设置",
            description: "打开设置",
            available: true,
            execute,
          },
        ]}
        onSend={vi.fn()}
      />,
    );

    const input = screen.getByRole("textbox", { name: "消息" }) as HTMLTextAreaElement;
    input.focus();
    input.setSelectionRange(4, 7);
    fireEvent.keyUp(input, { key: "ArrowLeft" });
    const option = screen.getByRole("option", { name: /\/settings/ });
    await user.click(option);

    await waitFor(() => expect(input).toHaveValue(originalText));
    await waitFor(() => {
      expect(input).toHaveFocus();
      expect(input.selectionStart).toBe(4);
      expect(input.selectionEnd).toBe(7);
    });
    expect(screen.getByRole("alert")).toHaveTextContent("指令未完成，请重试。");

    await user.click(screen.getByRole("option", { name: /\/settings/ }));
    await waitFor(() => expect(execute).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(input).toHaveValue(" 后继续"));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  /** 先执行已经排入的绘制帧，再让外层 owner 提交恢复文本；原生 value 写入不得把 selection 推到末尾。 */
  it("Slash action 失败后 RAF 先于恢复草稿提交仍保留原选区", async () => {
    const user = userEvent.setup();
    const frames: FrameRequestCallback[] = [];
    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      frames.push(callback);
      return frames.length;
    });
    vi.spyOn(window, "cancelAnimationFrame").mockImplementation(() => {});
    const originalText = "/settings 后继续";
    let commitRecovery: (() => void) | undefined;
    render(
      <ControlledComposerHarness
        initialText={originalText}
        onDraftCommit={(nextText, commit) => {
          if (nextText === originalText) commitRecovery = commit;
          else commit();
        }}
        slashCommands={[
          {
            id: "settings",
            name: "settings",
            aliases: ["设置"],
            label: "设置",
            description: "打开设置",
            available: true,
            execute: () => Promise.reject(new Error("private command failure")),
          },
        ]}
        onSend={vi.fn()}
      />,
    );

    const input = screen.getByRole("textbox", { name: "消息" }) as HTMLTextAreaElement;
    input.focus();
    input.setSelectionRange(4, 4);
    fireEvent.keyUp(input, { key: "ArrowLeft" });
    const framesBeforeAction = frames.length;
    await user.click(screen.getByRole("option", { name: /\/settings/ }));
    await waitFor(() => expect(commitRecovery).toBeDefined());
    expect(input).toHaveValue(" 后继续");

    const frameBatch = frames.splice(framesBeforeAction);
    await act(async () => {
      for (const frame of frameBatch) frame(0);
    });
    expect(input).toHaveValue(" 后继续");

    const commit = commitRecovery;
    expect(commit).toBeDefined();
    act(() => commit?.());
    await waitFor(() => expect(input).toHaveValue(originalText));
    expect(input).toHaveFocus();
    expect(input.selectionStart).toBe(4);
    expect(screen.getByRole("alert")).toHaveTextContent("指令未完成，请重试。");
  });

  /** Scope 切换后尚未提交的恢复请求作废，不能让旧命令的 RAF 抢走新会话焦点。 */
  it("Slash action 失败后 scope 切换会丢弃迟到的恢复焦点", async () => {
    const user = userEvent.setup();
    const frames: FrameRequestCallback[] = [];
    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      frames.push(callback);
      return frames.length;
    });
    vi.spyOn(window, "cancelAnimationFrame").mockImplementation(() => {});
    const originalText = "/settings 后继续";
    let commitRecovery: (() => void) | undefined;
    /** 命令失败时暂缓恢复值，让测试在中间绘制帧后切换 scope。 */
    const interceptDraftCommit = (nextText: string, commit: () => void): void => {
      if (nextText === originalText) commitRecovery = commit;
      else commit();
    };
    const props = {
      initialText: originalText,
      onDraftCommit: interceptDraftCommit,
      preferences: PREFERENCES,
      models: MODELS,
      slashCommands: [
        {
          id: "settings",
          name: "settings",
          aliases: ["设置"],
          label: "设置",
          description: "打开设置",
          available: true,
          execute: () => Promise.reject(new Error("private command failure")),
        },
      ],
      onSend: vi.fn(),
    } satisfies ControlledComposerHarnessProps;
    const view = render(<ControlledComposerHarness {...props} threadId="thread_old" />);
    const input = screen.getByRole("textbox", { name: "消息" });
    input.focus();
    (input as HTMLTextAreaElement).setSelectionRange(4, 4);
    fireEvent.keyUp(input, { key: "ArrowLeft" });
    const framesBeforeAction = frames.length;
    await user.click(screen.getByRole("option", { name: /\/settings/ }));
    await waitFor(() => expect(commitRecovery).toBeDefined());

    const modelTrigger = screen.getByRole("button", { name: /当前模型/ });
    modelTrigger.focus();
    expect(modelTrigger).toHaveFocus();
    view.rerender(<ControlledComposerHarness {...props} threadId="thread_new" />);
    await act(async () => {
      for (const frame of frames.splice(framesBeforeAction)) frame(0);
    });

    expect(modelTrigger).toHaveFocus();
    expect(screen.getByRole("textbox", { name: "消息" })).toHaveValue(" 后继续");
  });

  /** Scope/generation 变化立即关闭当前面板，同一草稿必须再次变化才开始新请求。 */
  it("丢弃 Scope 切换后的触发会话与迟到路径结果", async () => {
    let resolveSearch!: (value: {
      threadId: string;
      workspaceId: string;
      generation: number;
      query: string;
      items: { relativePath: string; kind: "file" }[];
      truncated: boolean;
    }) => void;
    const searchPaths = vi.fn(
      () =>
        new Promise<{
          threadId: string;
          workspaceId: string;
          generation: number;
          query: string;
          items: { relativePath: string; kind: "file" }[];
          truncated: boolean;
        }>((resolve) => {
          resolveSearch = resolve;
        }),
    );
    const props = {
      text: "@src",
      workspaceId: "ws_one",
      runtimeGeneration: 1,
      onTextChange: vi.fn(),
      onSearchWorkspacePaths: searchPaths,
      onSend: vi.fn(),
    };
    const { rerender } = render(<Composer {...props} threadId="thr_one" />);
    await waitFor(() => expect(searchPaths).toHaveBeenCalledOnce());
    rerender(<Composer {...props} threadId="thr_two" />);
    await waitFor(() =>
      expect(screen.queryByRole("listbox", { name: "文件与目录" })).not.toBeInTheDocument(),
    );
    resolveSearch({
      threadId: "thr_one",
      workspaceId: "ws_one",
      generation: 1,
      query: "src",
      items: [{ relativePath: "src/late.ts", kind: "file" }],
      truncated: false,
    });
    await Promise.resolve();
    expect(screen.queryByText("late.ts")).not.toBeInTheDocument();
    rerender(<Composer {...props} text="@srcx" threadId="thr_two" />);
    await waitFor(() => expect(screen.getByRole("listbox", { name: "文件与目录" })).toBeVisible());
  });

  /** 恢复 revision 是鼠标准入失败后的确定信号，原 selection 与 textarea 焦点必须一并恢复。 */
  it("准入失败恢复 revision 后归还原选择区与输入焦点", async () => {
    const user = userEvent.setup();
    const props = {
      initialText: "检查失效引用",
      initialContextReferences: [
        {
          type: "workspace_reference" as const,
          workspaceId: "ws_one",
          relativePath: "gone.ts",
          kind: "file" as const,
        },
      ],
      onSend: vi.fn(),
    };
    const { rerender } = render(<ControlledComposerHarness {...props} draftRecoveryRevision={0} />);
    const input = screen.getByRole("textbox", { name: "消息" });
    input.focus();
    (input as HTMLTextAreaElement).setSelectionRange(2, 6);
    fireEvent.select(input);
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(input).not.toHaveFocus();
    rerender(<ControlledComposerHarness {...props} draftRecoveryRevision={1} />);
    await waitFor(() => expect(input).toHaveFocus());
    expect((input as HTMLTextAreaElement).selectionStart).toBe(2);
    expect((input as HTMLTextAreaElement).selectionEnd).toBe(6);
  });

  it("活动 Turn 空草稿显示停止，输入后切换发送并在入队清空后恢复停止", async () => {
    const user = userEvent.setup();
    const enqueue = vi.fn();
    const cancel = vi.fn();
    render(
      <ActiveQueueComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        onSend={vi.fn()}
        onEnqueue={enqueue}
        onCancel={cancel}
      />,
    );
    expect(screen.queryByRole("button", { name: "发送" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "加入队列" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "停止生成" })).toBeEnabled();
    const input = screen.getByRole("textbox", { name: "消息" });
    await user.type(input, "第一行");
    await user.keyboard("{Shift>}{Enter}{/Shift}");
    await user.type(input, "第二行");
    expect(screen.getByRole("button", { name: "排队发送" })).toBeEnabled();
    await user.keyboard("{Enter}");
    expect(enqueue).toHaveBeenCalledWith({
      text: "第一行\n第二行",
      attachmentIds: [],
      contextReferences: [],
    });
    expect(input).toHaveValue("");
    expect(screen.getByRole("button", { name: "停止生成" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "停止生成" }));
    expect(cancel).toHaveBeenCalledOnce();
  });

  /** 空草稿续答与有内容发送共用一个位置，但动作语义必须明确切换。 */
  it("最新失败轮次在空草稿时显示继续，输入或附件草稿后恢复发送语义", async () => {
    const user = userEvent.setup();
    const continueReply = vi.fn();
    const send = vi.fn();
    const { rerender } = render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        continuationAvailable
        onContinue={continueReply}
        onSend={send}
      />,
    );

    const continueButton = screen.getByRole("button", { name: "继续回复" });
    expect(continueButton).toBeEnabled();
    expect(continueButton.textContent).toBe("");
    await user.click(continueButton);
    expect(continueReply).toHaveBeenCalledOnce();
    expect(send).not.toHaveBeenCalled();
    await user.type(screen.getByRole("textbox", { name: "消息" }), "补充说明");
    expect(screen.getByRole("button", { name: "发送" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "继续回复" })).not.toBeInTheDocument();

    rerender(
      <ControlledComposerHarness
        key="attachment-draft"
        preferences={PREFERENCES}
        models={MODELS}
        continuationAvailable
        attachmentDraftItems={[
          {
            state: "importing",
            operationId: "operation_importing",
            attemptId: "attempt_importing",
            itemId: "item_importing",
            fileName: "正在导入.txt",
            sizeBytes: 32,
            mediaKind: "text",
            phase: "copying",
            bytesCopied: 0,
          },
        ]}
        onContinue={continueReply}
        onSend={send}
      />,
    );
    expect(screen.queryByRole("button", { name: "继续回复" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "发送" })).toBeDisabled();

    rerender(
      <ControlledComposerHarness
        key="skill-draft"
        preferences={PREFERENCES}
        models={MODELS}
        continuationAvailable
        initialContextReferences={[{ type: "skill_reference", skillId: "skill_one" }]}
        onContinue={continueReply}
        onSend={send}
      />,
    );
    expect(screen.queryByRole("button", { name: "继续回复" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "发送" })).toBeDisabled();
  });

  it("空草稿的发送快捷键不触发续答", async () => {
    const user = userEvent.setup();
    const continueReply = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        continuationAvailable
        onContinue={continueReply}
        onSend={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("textbox", { name: "消息" }));
    await user.keyboard("{Control>}{Enter}{/Control}");
    expect(continueReply).not.toHaveBeenCalled();
  });

  /** ready 附件本身就是可提交内容，活动 Turn 不要求用户补一段占位正文。 */
  it("活动 Turn 可将仅附件草稿加入队列", async () => {
    const user = userEvent.setup();
    const enqueue = vi.fn();
    render(
      <ControlledComposerHarness
        activeTurn
        preferences={PREFERENCES}
        models={MODELS}
        attachmentDraftItems={[
          {
            state: "ready",
            itemId: "item_only",
            attachmentId: "att_only",
            fileName: "需求截图.png",
            sizeBytes: 4096,
            mediaKind: "image",
            mediaType: "image/png",
          },
        ]}
        onSend={vi.fn()}
        onEnqueue={enqueue}
        onCancel={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "排队发送" }));
    expect(enqueue).toHaveBeenCalledWith({
      text: "",
      attachmentIds: ["att_only"],
      contextReferences: [],
    });
  });

  /** 附件摘要属于队列消息本身；移除后仍有正文则更新，附件是唯一内容时删除空消息。 */
  it("展示并移除队列附件且不留下空输入", async () => {
    const user = userEvent.setup();
    const update = vi.fn();
    const remove = vi.fn();
    const openPreview = vi.fn();
    render(
      <ControlledComposerHarness
        activeTurn
        queuedInputs={[
          {
            inputId: "input_with_text",
            content: [
              { type: "attachment", attachmentId: "att_spec" },
              { type: "text", text: "结合附件继续" },
            ],
            attachments: [
              {
                attachmentId: "att_spec",
                displayName: "设计说明.pdf",
                sizeBytes: 2048,
                mediaKind: "pdf",
                mediaType: "application/pdf",
              },
            ],
            kind: "follow_up",
            status: "needs_attention",
            issue: {
              errorCode: "ATTACHMENT_UNAVAILABLE",
              message: "附件已不可用",
              retryable: true,
            },
            inputRevision: 2,
            createdAt: "2026-09-03T00:00:00Z",
          },
          {
            inputId: "input_attachment_only",
            content: [{ type: "attachment", attachmentId: "att_only" }],
            attachments: [
              {
                attachmentId: "att_only",
                displayName: "日志.txt",
                sizeBytes: 128,
                mediaKind: "text",
                mediaType: "text/plain",
              },
            ],
            kind: "follow_up",
            status: "pending",
            issue: null,
            inputRevision: 4,
            createdAt: "2026-09-03T00:00:01Z",
          },
        ]}
        onSend={vi.fn()}
        onEnqueue={vi.fn()}
        onCancel={vi.fn()}
        onUpdateQueuedInput={update}
        onDeleteQueuedInput={remove}
        onOpenQueuedAttachmentPreview={openPreview}
      />,
    );

    expect(screen.getByText("设计说明.pdf")).toBeVisible();
    expect(screen.getByText("日志.txt")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "预览附件 日志.txt" }));
    expect(openPreview).toHaveBeenCalledWith(
      {
        attachmentId: "att_only",
        fileName: "日志.txt",
        sizeBytes: 128,
        mediaKind: "text",
        mediaType: "text/plain",
      },
      expect.any(HTMLButtonElement),
    );
    await user.click(
      screen.getByRole("button", { name: /第 1 条消息：结合附件继续移除附件 设计说明\.pdf/ }),
    );
    expect(update).toHaveBeenCalledWith("input_with_text", 2, [
      { type: "text", text: "结合附件继续" },
    ]);
    await user.click(
      screen.getByRole("button", { name: /第 2 条消息：日志\.txt移除附件 日志\.txt/ }),
    );
    expect(remove).toHaveBeenCalledWith("input_attachment_only", 4);
  });

  /** 高频调整与删除直接贴附对象，低频编辑通过更多菜单进入且保持完整键盘闭环。 */
  it("展示真实队列顺序并支持调整方向、删除与内联编辑键盘操作", async () => {
    const user = userEvent.setup();
    const prioritize = vi.fn();
    const update = vi.fn();
    const remove = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        activeTurn
        queuedInputs={[
          {
            inputId: "input_steering",
            content: [{ type: "text", text: "先检查测试失败" }],
            attachments: [],
            kind: "steering",
            status: "pending",
            issue: null,
            inputRevision: 5,
            createdAt: "2026-09-01T01:00:00Z",
          },
          {
            inputId: "input_follow_up",
            content: [{ type: "text", text: "然后补充交付说明" }],
            attachments: [],
            kind: "follow_up",
            status: "pending",
            issue: null,
            inputRevision: 2,
            createdAt: "2026-09-01T01:00:01Z",
          },
        ]}
        onSend={vi.fn()}
        onEnqueue={vi.fn()}
        onCancel={vi.fn()}
        onPrioritizeQueuedInput={prioritize}
        onUpdateQueuedInput={update}
        onDeleteQueuedInput={remove}
      />,
    );

    const queue = screen.getByRole("list", { name: "排队消息" });
    const rows = within(queue).getAllByRole("listitem");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("先检查测试失败");
    expect(rows[1]).toHaveTextContent("然后补充交付说明");
    expect(screen.getByRole("button", { name: /已调整方向：第 1 条消息/ })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: /调整方向：第 2 条消息/ }));
    expect(prioritize).toHaveBeenCalledWith("input_follow_up", 2);
    await user.click(screen.getByRole("button", { name: /删除第 2 条消息/ }));
    expect(remove).toHaveBeenCalledWith("input_follow_up", 2);

    await user.click(screen.getByRole("button", { name: /更多操作：第 2 条消息/ }));
    await user.click(await screen.findByRole("menuitem", { name: "编辑消息" }));
    const editor = screen.getByRole("textbox", { name: /编辑第 2 条消息/ });
    await user.clear(editor);
    await user.type(editor, "修正方向{Shift>}{Enter}{/Shift}补充");
    expect(update).not.toHaveBeenCalled();
    await user.keyboard("{Enter}");
    expect(update).toHaveBeenCalledWith("input_follow_up", 2, [
      { type: "text", text: "修正方向\n补充" },
    ]);
    expect(screen.queryByRole("textbox", { name: /编辑第 2 条消息/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /更多操作：第 2 条消息/ }));
    await user.click(await screen.findByRole("menuitem", { name: "编辑消息" }));
    await user.clear(screen.getByRole("textbox", { name: /编辑第 2 条消息/ }));
    await user.type(screen.getByRole("textbox", { name: /编辑第 2 条消息/ }), "不保存");
    await user.keyboard("{Escape}");
    expect(update).toHaveBeenCalledOnce();
    expect(rows[1]).toHaveTextContent("然后补充交付说明");
  });

  /** needs_attention 允许从对象内部移除失效引用并以同一 revision 原位更新，不迫使删除整条。 */
  it("编辑失效队列引用并保存结构化重试内容", async () => {
    const user = userEvent.setup();
    const update = vi.fn();
    render(
      <ControlledComposerHarness
        activeTurn
        queuedInputs={[
          {
            inputId: "input_attention",
            content: [
              { type: "skill_reference", skillId: "skill_removed" },
              {
                type: "workspace_reference",
                workspaceId: "ws_one",
                relativePath: "src/valid.ts",
                kind: "file",
              },
              { type: "text", text: "继续处理" },
            ],
            attachments: [],
            kind: "follow_up",
            status: "needs_attention",
            issue: {
              errorCode: "SKILL_UNAVAILABLE",
              message: "Skill 已不可用",
              retryable: true,
            },
            inputRevision: 7,
            createdAt: "2026-09-03T00:00:00Z",
          },
        ]}
        onSend={vi.fn()}
        onEnqueue={vi.fn()}
        onCancel={vi.fn()}
        onUpdateQueuedInput={update}
        onDeleteQueuedInput={vi.fn()}
        onPrioritizeQueuedInput={vi.fn()}
      />,
    );

    expect(screen.getByText("Skill 不可用")).toBeVisible();
    expect(screen.queryByText("skill_removed")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /更多操作：第 1 条消息/ }));
    await user.click(await screen.findByRole("menuitem", { name: "编辑消息" }));
    await user.click(screen.getByRole("button", { name: "移除上下文 Skill 不可用" }));
    await user.click(screen.getByRole("button", { name: /保存编辑第 1 条消息/ }));
    expect(update).toHaveBeenCalledWith("input_attention", 7, [
      {
        type: "workspace_reference",
        workspaceId: "ws_one",
        relativePath: "src/valid.ts",
        kind: "file",
      },
      { type: "text", text: "继续处理" },
    ]);
  });

  /** 临时、操作中和错误投影必须可辨认且禁止重复动作，队列关闭时保留草稿但不伪装可发送。 */
  it("完整呈现 pending、busy、error 与停止接收状态", async () => {
    const user = userEvent.setup();
    render(
      <ControlledComposerHarness
        initialText="继续排队"
        preferences={PREFERENCES}
        models={MODELS}
        activeTurn
        queueAccepting={false}
        queuedInputs={[
          {
            inputId: "temp_1",
            content: [{ type: "text", text: "正在提交的消息" }],
            attachments: [],
            kind: "follow_up",
            status: "pending",
            issue: null,
            inputRevision: 0,
            createdAt: "2026-09-01T01:00:00Z",
            pending: true,
          },
          {
            inputId: "input_busy",
            content: [{ type: "text", text: "正在编辑的消息" }],
            attachments: [],
            kind: "follow_up",
            status: "pending",
            issue: null,
            inputRevision: 3,
            createdAt: "2026-09-01T01:00:01Z",
            busyAction: "update",
          },
          {
            inputId: "input_error",
            content: [{ type: "text", text: "需要重试的消息" }],
            attachments: [],
            kind: "follow_up",
            status: "pending",
            issue: null,
            inputRevision: 4,
            createdAt: "2026-09-01T01:00:02Z",
            error: "队列版本已变化，请重试",
          },
        ]}
        onSend={vi.fn()}
        onEnqueue={vi.fn()}
        onCancel={vi.fn()}
        onPrioritizeQueuedInput={vi.fn()}
        onUpdateQueuedInput={vi.fn()}
        onDeleteQueuedInput={vi.fn()}
      />,
    );

    expect(screen.getByText("正在排队")).toBeVisible();
    expect(screen.getByText("队列版本已变化，请重试")).toHaveRole("alert");
    expect(screen.getByRole("button", { name: /调整方向：第 1 条消息/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /更多操作：第 2 条消息/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: "排队发送" })).toBeDisabled();
    await user.click(screen.getByRole("textbox", { name: "消息" }));
    await user.keyboard("{Enter}");
    expect(screen.getByRole("textbox", { name: "消息" })).toHaveValue("继续排队");
  });

  /** Suspended 保留草稿与附件准备能力，但恢复前不接纳新输入。 */
  it("中断 Turn 可继续编辑和导入附件但只显示继续与取消动作", async () => {
    const user = userEvent.setup();
    const resume = vi.fn();
    const cancel = vi.fn();
    const send = vi.fn();
    const enqueue = vi.fn();
    const addAttachments = vi.fn();
    const pasteAttachments = vi.fn();
    const dropAttachments = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        initialText="下一条消息"
        suspendedTurn
        onSend={send}
        onEnqueue={enqueue}
        onResume={resume}
        onCancel={cancel}
        onAddAttachments={addAttachments}
        onPasteAttachments={pasteAttachments}
        onDropAttachments={dropAttachments}
        nativeDropEvent={{
          phase: "drop",
          x: 12,
          y: 12,
          count: 1,
          dropToken: "drop_suspended",
        }}
      />,
    );

    expect(screen.getByText("已暂停")).toBeVisible();
    expect(screen.queryByRole("combobox", { name: "消息队列" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "发送" })).not.toBeInTheDocument();
    const input = screen.getByRole("textbox", { name: "消息" });
    expect(input).toBeEnabled();
    await user.type(input, "，恢复后处理");
    await user.click(screen.getByRole("button", { name: "添加附件" }));
    const paste = new Event("paste", { bubbles: true, cancelable: true });
    Object.defineProperty(paste, "clipboardData", { value: { getData: () => "", items: [] } });
    input.dispatchEvent(paste);
    expect(addAttachments).toHaveBeenCalledOnce();
    expect(pasteAttachments).toHaveBeenCalledOnce();
    expect(dropAttachments).toHaveBeenCalledWith("drop_suspended");
    await user.click(screen.getByRole("button", { name: "继续运行" }));
    expect(resume).toHaveBeenCalledOnce();
    await user.click(screen.getByRole("button", { name: "取消运行" }));
    expect(cancel).toHaveBeenCalledOnce();
    expect(send).not.toHaveBeenCalled();
    expect(enqueue).not.toHaveBeenCalled();
  });

  /** 含缓存输入与输出组成总量；缓存只作输入明细，最近请求窗口不参与累计计算。 */
  it("按需展示本会话累计用量、部分数据和最近请求窗口", async () => {
    const user = userEvent.setup();
    const reader = { read: vi.fn().mockResolvedValue(USAGE_SUMMARY) };
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        threadId="thr_usage"
        runtimeGeneration={4}
        usageReader={reader}
        contextUsage={{
          certainty: "known",
          usedTokens: 55_000,
          limitTokens: 272_000,
          percentage: 20.2,
          ringPercentage: 20.220588235294116,
          tone: "neutral",
          source: "provider",
          measuredAt: "2026-08-31T00:00:00Z",
        }}
        onSend={vi.fn()}
      />,
    );
    const indicator = screen.getByRole("progressbar", { name: "上下文使用量" });
    expect(indicator).toHaveAttribute("data-tone", "neutral");
    expect(indicator).toHaveAttribute("aria-valuenow", "20.2");
    expect(indicator).toHaveAttribute(
      "aria-valuetext",
      "当前上下文 20.2%，55k / 272k tokens，Provider 完整用量",
    );
    const trigger = screen.getByRole("button", { name: "上下文用量详情" });
    await user.click(trigger);
    await waitFor(() => expect(reader.read).toHaveBeenCalledWith({ threadId: "thr_usage" }));
    expect(await screen.findByText("Token · 本会话")).toBeVisible();
    expect(screen.getByText("输入（含缓存）")).toBeVisible();
    expect(screen.getByText("20k")).toBeVisible();
    expect(screen.getByText("2.1k")).toBeVisible();
    expect(screen.getByText("6.2k")).toBeVisible();
    expect(screen.getByText("900")).toBeVisible();
    expect(screen.getByText("总计（输入 + 输出）")).toBeVisible();
    expect(screen.getByText("22k")).toBeVisible();
    expect(screen.getByText("缓存已计入输入和总计，不重复累加。")).toBeVisible();
    expect(screen.getByText("最近请求的占用，估算校正或压缩后可能降低。")).toBeVisible();
    expect(screen.getByText("31.8%")).toBeVisible();
    expect(screen.queryByText("部分数据")).toBeNull();
    expect(screen.getByText("部分请求未上报全部用量字段")).toBeVisible();
    expect(screen.getByText("当前上下文 · Provider 实测")).toBeVisible();
    expect(screen.getByText("20.2%")).toBeVisible();
    expect(screen.getByText("55k / 272k")).toBeVisible();
  });

  /** 不到 0.1% 的小占用仍应可读，压缩后的 envelope 预算也要明确标为本地估算。 */
  it("以小于百分之一显示极小占用并标注压缩后的本地估算", async () => {
    const user = userEvent.setup();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        threadId="thr_usage_estimated"
        usageReader={{
          read: vi.fn().mockResolvedValue({ ...USAGE_SUMMARY, threadId: "thr_usage_estimated" }),
        }}
        contextUsage={{
          certainty: "estimated",
          usedTokens: 143,
          limitTokens: 1_000_000,
          percentage: 0,
          ringPercentage: 0.0143,
          tone: "neutral",
          source: "local",
          measuredAt: "2026-09-26T00:00:00Z",
        }}
        onSend={vi.fn()}
      />,
    );
    expect(screen.getByRole("progressbar", { name: "上下文使用量" })).toHaveAttribute(
      "aria-valuetext",
      "当前上下文约 <0.1%，143 / 1M tokens，本地估算",
    );
    await user.click(screen.getByRole("button", { name: "上下文用量详情" }));
    expect(await screen.findByText("当前上下文 · 本地估算")).toBeVisible();
    expect(screen.getByText("约 <0.1%")).toBeVisible();
  });

  /** 聚合账本离线时仍展示已收到的当前上下文，不让两种统计互相阻断。 */
  it("累计用量读取失败时保留当前上下文估算", async () => {
    const user = userEvent.setup();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        threadId="thr_usage_unavailable"
        usageReader={{ read: vi.fn().mockRejectedValue(new Error("offline")) }}
        contextUsage={{
          certainty: "estimated",
          usedTokens: 18_500,
          limitTokens: 128_000,
          percentage: 14.5,
          ringPercentage: 14.453125,
          tone: "neutral",
          source: "local",
          measuredAt: "2026-09-26T00:00:00Z",
        }}
        onSend={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "上下文用量详情" }));

    expect(await screen.findByText("当前上下文 · 本地估算")).toBeVisible();
    expect(screen.getByText("19k / 128k")).toBeVisible();
    expect(await screen.findByText("用量暂不可用")).toBeVisible();
  });

  /** 缓存写入未报告不阻止完整 total/output 推导含缓存输入，也不把缺失写入伪造成零。 */
  it("按 API 输入与缓存读取计算没有写入字段的命中率", async () => {
    const user = userEvent.setup();
    const reader = {
      read: vi.fn().mockResolvedValue({
        threadId: "thr_openai_usage",
        snapshotRevision: 8,
        requestCount: 1,
        measuredRequestCount: 1,
        newInputRequestCount: 1,
        newInputTokens: 227_019,
        outputRequestCount: 1,
        outputTokens: 10_778,
        totalRequestCount: 1,
        totalTokens: 2_559_205,
        cacheReadRequestCount: 1,
        cacheReadTokens: 2_321_408,
        cacheWriteRequestCount: 0,
        cacheWriteTokens: 0,
        cacheCompleteRequestCount: 1,
        cacheCompleteInputTokens: 2_548_427,
        cacheCompleteReadTokens: 2_321_408,
      }),
    };
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        threadId="thr_openai_usage"
        usageReader={reader}
        onSend={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "上下文用量详情" }));
    expect(await screen.findByText("91.1%")).toBeVisible();
    expect(screen.getByText("2.5M")).toBeVisible();
    expect(screen.getByText("11k")).toBeVisible();
    expect(screen.getByText("2.3M")).toBeVisible();
    expect(screen.getByText("2.6M")).toBeVisible();
    expect(screen.queryByText("含缓存写入")).toBeNull();
  });

  /** 部分 total/output 可能来自不同请求；缓存只扣减一次，已报告的零保留输入与写入明细。 */
  it.each([
    { name: "完整输入含缓存写入", overrides: {}, expected: "24k" },
    {
      name: "未计量请求仅展示已报告小计",
      overrides: { requestCount: 4 },
      expected: "24k",
    },
    {
      name: "数量相同但各自部分覆盖",
      overrides: { totalRequestCount: 2, outputRequestCount: 2 },
      expected: "—",
    },
    { name: "未报告总量", overrides: { totalRequestCount: 0, totalTokens: 0 }, expected: "—" },
    { name: "总量小于输出", overrides: { totalTokens: 100 }, expected: "—" },
    {
      name: "完整报告零用量",
      overrides: {
        totalTokens: 0,
        outputTokens: 0,
        newInputTokens: 0,
        cacheReadTokens: 0,
        cacheWriteTokens: 0,
        cacheCompleteInputTokens: 0,
        cacheCompleteReadTokens: 0,
      },
      expected: "0",
    },
  ])("含缓存累计输入保持可信覆盖：$name", async ({ name, overrides, expected }) => {
    const user = userEvent.setup();
    const threadId = "thr_inclusive_" + name;
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        threadId={threadId}
        usageReader={{
          read: vi.fn().mockResolvedValue({
            ...USAGE_SUMMARY,
            threadId,
            newInputTokens: 920,
            outputTokens: 449,
            totalTokens: 24_449,
            cacheReadTokens: 22_080,
            cacheWriteTokens: 1_000,
            cacheCompleteInputTokens: 24_000,
            cacheCompleteReadTokens: 22_080,
            ...overrides,
          }),
        }}
        onSend={vi.fn()}
      />,
    );
    await user.click(screen.getByRole("button", { name: "上下文用量详情" }));
    const label = await screen.findByText("输入（含缓存）");
    expect(within(label.parentElement!).getByText(expected)).toBeVisible();
    if (name === "完整输入含缓存写入") {
      const write = screen.getByText("含缓存写入");
      expect(within(write.parentElement!).getByText("1k")).toBeVisible();
    }
    if (name === "完整报告零用量") {
      const write = screen.getByText("含缓存写入");
      expect(within(write.parentElement!).getByText("0")).toBeVisible();
    }
    if (name === "未计量请求仅展示已报告小计") {
      expect(screen.getByText("累计仅含已报告用量，仍有请求未上报用量。")).toBeVisible();
    }
  });

  /** Provider 未报告计量时保持未知，账本为空也只引导发送消息，不能伪造成 0 Token。 */
  it("以未知态保留固定圆环并在无请求时给出可恢复提示", async () => {
    const user = userEvent.setup();
    const reader = {
      read: vi.fn().mockResolvedValue({
        ...USAGE_SUMMARY,
        threadId: "thr_usage_empty",
        requestCount: 0,
        measuredRequestCount: 0,
        newInputRequestCount: 0,
        outputRequestCount: 0,
        totalRequestCount: 0,
        cacheReadRequestCount: 0,
        cacheWriteRequestCount: 0,
        cacheCompleteRequestCount: 0,
      }),
    };
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        threadId="thr_usage_empty"
        usageReader={reader}
        contextUsage={{
          certainty: "unknown",
          source: "provider",
          measuredAt: "2026-09-22T00:00:00Z",
        }}
        onSend={vi.fn()}
      />,
    );
    expect(screen.queryByRole("progressbar", { name: "上下文使用量" })).toBeNull();
    const indicator = screen.getByRole("img", { name: "上下文使用量待确认" });
    expect(indicator).toHaveAttribute("data-tone", "unknown");
    await user.click(screen.getByRole("button", { name: "上下文用量详情" }));
    expect(await screen.findByText("发送消息后显示用量")).toBeVisible();
  });

  /** Provider 已有请求账目但尚无任何 Usage 字段时，只说明状态，不把未知项铺成破折号。 */
  it("用一句状态提示代替尚未报告的累计用量空行", async () => {
    const user = userEvent.setup();
    const reader = {
      read: vi.fn().mockResolvedValue({
        ...USAGE_SUMMARY,
        threadId: "thr_usage_unreported",
        requestCount: 1,
        measuredRequestCount: 0,
        newInputRequestCount: 0,
        newInputTokens: 0,
        outputRequestCount: 0,
        outputTokens: 0,
        totalRequestCount: 0,
        totalTokens: 0,
        cacheReadRequestCount: 0,
        cacheReadTokens: 0,
        cacheWriteRequestCount: 0,
        cacheWriteTokens: 0,
        cacheCompleteRequestCount: 0,
        cacheCompleteInputTokens: 0,
        cacheCompleteReadTokens: 0,
      }),
    };
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        threadId="thr_usage_unreported"
        usageReader={reader}
        contextUsage={{
          certainty: "estimated",
          usedTokens: 3_785,
          limitTokens: 128_000,
          percentage: 3,
          ringPercentage: 2.95703125,
          tone: "neutral",
          source: "local",
          measuredAt: "2026-09-26T00:00:00Z",
        }}
        onSend={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "上下文用量详情" }));

    expect(await screen.findByText("当前上下文 · 本地估算")).toBeVisible();
    expect(screen.getByText("约 3.0%")).toBeVisible();
    expect(screen.getByText("3.8k / 128k")).toBeVisible();
    expect(await screen.findByText("尚无已报告的 Provider 用量")).toBeVisible();
    expect(screen.queryByText("缓存命中率")).toBeNull();
    expect(screen.queryByText("输入（含缓存）")).toBeNull();
  });

  /** 已确认快照在刷新失败时保留，避免请求结算中的瞬时故障把用户可读数据闪成空白。 */
  it("刷新失败时保留旧值并提示暂未更新", async () => {
    const user = userEvent.setup();
    const reader = {
      read: vi
        .fn()
        .mockResolvedValueOnce({ ...USAGE_SUMMARY, threadId: "thr_usage_stale" })
        .mockRejectedValueOnce(new Error("offline")),
    };
    const { rerender } = render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        threadId="thr_usage_stale"
        usageReader={reader}
        usageRefreshRevision="usage-1"
        onSend={vi.fn()}
      />,
    );
    const trigger = screen.getByRole("button", { name: "上下文用量详情" });
    await user.click(trigger);
    expect(await screen.findByText("22k")).toBeVisible();

    rerender(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        threadId="thr_usage_stale"
        usageReader={reader}
        usageRefreshRevision="usage-2"
        onSend={vi.fn()}
      />,
    );
    expect(await screen.findByText("暂未更新")).toBeVisible();
    expect(screen.getByText("22k")).toBeVisible();
  });

  /** 归还入口焦点不能重开浮层；真实 Radix 卸载与后续帧结束后仍须保持关闭。 */
  it.each(["入口", "浮层"] as const)(
    "支持悬停阅读、固定展开、外部关闭和 Escape 焦点回归（焦点在%s）",
    async (escapeFocus) => {
      const user = userEvent.setup();
      const reader = {
        read: vi
          .fn()
          .mockImplementation((input: { threadId: string }) =>
            Promise.resolve({ ...USAGE_SUMMARY, threadId: input.threadId }),
          ),
      };
      render(
        <ControlledComposerHarness
          preferences={PREFERENCES}
          models={MODELS}
          threadId="thr_usage_hover"
          usageReader={reader}
          onSend={vi.fn()}
        />,
      );
      const trigger = screen.getByRole("button", { name: "上下文用量详情" });
      await user.hover(trigger);
      expect(await screen.findByText("Token · 本会话")).toBeVisible();
      await user.click(trigger);
      await user.click(document.body);
      await waitFor(() => expect(screen.queryByText("Token · 本会话")).toBeNull());

      await user.click(trigger);
      expect(await screen.findByText("Token · 本会话")).toBeVisible();
      if (escapeFocus === "浮层") {
        const dialog = screen.getByRole("dialog", { name: "上下文用量详情" });
        act(() => dialog.focus());
        expect(dialog).toHaveFocus();
      }
      await user.keyboard("{Escape}");
      await waitFor(() => expect(screen.queryByText("Token · 本会话")).toBeNull());
      await waitFor(() => expect(trigger).toHaveFocus());
      await act(async () => {
        await new Promise<void>((resolve) => {
          window.requestAnimationFrame(() => window.requestAnimationFrame(() => resolve()));
        });
      });
      expect(screen.queryByRole("dialog", { name: "上下文用量详情" })).toBeNull();
      expect(trigger).toHaveAttribute("aria-expanded", "false");
      expect(trigger).toHaveFocus();

      act(() => screen.getByRole("textbox", { name: "消息" }).focus());
      act(() => trigger.focus());
      expect(await screen.findByRole("dialog", { name: "上下文用量详情" })).toBeVisible();
      await user.keyboard("{Escape}");
      await waitFor(() => expect(screen.queryByText("Token · 本会话")).toBeNull());

      await user.unhover(trigger);
      await user.hover(trigger);
      expect(await screen.findByRole("dialog", { name: "上下文用量详情" })).toBeVisible();
      await user.click(trigger);
      await user.unhover(trigger);
      expect(screen.getByRole("dialog", { name: "上下文用量详情" })).toBeVisible();
      await user.click(document.body);
      await waitFor(() => expect(screen.queryByText("Token · 本会话")).toBeNull());
    },
  );

  /** 复现真窗原位置被 Portal 覆盖再露出的边界事件；没有新移动就不应把关闭当成新悬停。 */
  it("Escape 后 Portal 在同一指针位置退出不会重开，真实离开、聚焦和点击仍可展开", async () => {
    const user = userEvent.setup();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        threadId="thr_usage_portal_boundary"
        onSend={vi.fn()}
      />,
    );
    const trigger = screen.getByRole("button", { name: "上下文用量详情" });
    const svg = trigger.querySelector("svg")!;
    const point = { clientX: 885, clientY: 779 };
    vi.spyOn(trigger, "getBoundingClientRect").mockReturnValue(new DOMRect(871, 765, 28, 28));
    await user.pointer({ target: svg, coords: point });
    await user.click(trigger);
    const dialog = await screen.findByRole("dialog", { name: "上下文用量详情" });
    const section = within(dialog).getByRole("region", { name: "Token 本会话" });
    const pointerMove = vi.fn();
    trigger.addEventListener("pointermove", pointerMove);
    fireEvent(
      svg,
      new MouseEvent("pointerout", { bubbles: true, relatedTarget: section, ...point }),
    );
    fireEvent(
      section,
      new MouseEvent("pointerover", { bubbles: true, relatedTarget: svg, ...point }),
    );
    await user.keyboard("{Escape}");
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "上下文用量详情" })).toBeNull(),
    );
    await waitFor(() => expect(trigger).toHaveFocus());
    fireEvent(
      svg,
      new MouseEvent("pointerover", { bubbles: true, relatedTarget: section, ...point }),
    );
    expect(pointerMove).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog", { name: "上下文用量详情" })).toBeNull();
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveFocus();

    const input = screen.getByRole("textbox", { name: "消息" });
    await user.pointer({ target: input, coords: { clientX: 850, clientY: 779 } });
    await user.pointer({ target: svg, coords: point });
    expect(await screen.findByRole("dialog", { name: "上下文用量详情" })).toBeVisible();
    await user.keyboard("{Escape}");
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "上下文用量详情" })).toBeNull(),
    );
    act(() => screen.getByRole("button", { name: /当前模型/ }).focus());
    await user.tab({ shift: true });
    expect(trigger).toHaveFocus();
    expect(await screen.findByRole("dialog", { name: "上下文用量详情" })).toBeVisible();
    await user.keyboard("{Escape}");
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "上下文用量详情" })).toBeNull(),
    );
    await user.click(trigger);
    expect(await screen.findByRole("dialog", { name: "上下文用量详情" })).toBeVisible();
  });

  /** 浮层覆盖期间真实移动不再给入口 leave；浮层退出仍须清理已离开的入口区域。 */
  it("指针从覆盖入口的浮层移到入口区域外后关闭，重新进入仍能展开", async () => {
    const user = userEvent.setup();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        threadId="thr_usage_portal_exit"
        onSend={vi.fn()}
      />,
    );
    const trigger = screen.getByRole("button", { name: "上下文用量详情" });
    const svg = trigger.querySelector("svg")!;
    const input = screen.getByRole("textbox", { name: "消息" });
    const inside = { clientX: 885, clientY: 779 };
    const outside = { clientX: 850, clientY: 779 };
    vi.spyOn(trigger, "getBoundingClientRect").mockReturnValue(new DOMRect(871, 765, 28, 28));
    await user.click(input);
    await user.pointer({ target: svg, coords: inside });
    const dialog = await screen.findByRole("dialog", { name: "上下文用量详情" });
    const section = within(dialog).getByRole("region", { name: "Token 本会话" });
    fireEvent(
      svg,
      new MouseEvent("pointerout", { bubbles: true, relatedTarget: section, ...inside }),
    );
    fireEvent(
      section,
      new MouseEvent("pointerover", { bubbles: true, relatedTarget: svg, ...inside }),
    );
    fireEvent(section, new MouseEvent("pointermove", { bubbles: true, ...outside }));
    fireEvent(
      section,
      new MouseEvent("pointerout", { bubbles: true, relatedTarget: input, ...outside }),
    );
    fireEvent(
      input,
      new MouseEvent("pointerover", { bubbles: true, relatedTarget: section, ...outside }),
    );
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "上下文用量详情" })).toBeNull(),
    );
    expect(input).toHaveFocus();
    await user.pointer({ target: input, coords: outside });
    await user.pointer({ target: svg, coords: inside });
    expect(await screen.findByRole("dialog", { name: "上下文用量详情" })).toBeVisible();
  });

  /** 仅悬停没有入口 blur 可重置；关闭后首次真实 Tab 必须仍能展开，且关闭不抢原焦点。 */
  it.each(["离开入口", "外部点击"] as const)(
    "仅悬停后通过%s关闭，首次键盘聚焦仍展开用量详情",
    async (dismissal) => {
      const user = userEvent.setup();
      render(
        <ControlledComposerHarness
          preferences={PREFERENCES}
          models={MODELS}
          threadId="thr_usage_first_focus"
          onSend={vi.fn()}
        />,
      );
      const trigger = screen.getByRole("button", { name: "上下文用量详情" });
      const input = screen.getByRole("textbox", { name: "消息" });
      await user.click(input);
      await user.hover(trigger);
      expect(await screen.findByRole("dialog", { name: "上下文用量详情" })).toBeVisible();
      expect(input).toHaveFocus();

      if (dismissal === "离开入口") await user.unhover(trigger);
      else await user.click(input);
      await waitFor(() =>
        expect(screen.queryByRole("dialog", { name: "上下文用量详情" })).toBeNull(),
      );
      expect(input).toHaveFocus();

      act(() => screen.getByRole("button", { name: /当前模型/ }).focus());
      await user.tab({ shift: true });
      expect(trigger).toHaveFocus();
      expect(await screen.findByRole("dialog", { name: "上下文用量详情" })).toBeVisible();
    },
  );

  /** 新 Thread 打开后拒绝旧账本的迟到返回，避免缓存和视觉摘要串到当前会话。 */
  it("拒绝会话切换后的迟到用量响应", async () => {
    const user = userEvent.setup();
    let resolveFirst!: (summary: ConversationUsageSummary) => void;
    const firstRead = new Promise<ConversationUsageSummary>((resolve) => {
      resolveFirst = resolve;
    });
    const reader = {
      read: vi.fn((input: { threadId: string }) =>
        input.threadId === "thr_usage_a"
          ? firstRead
          : Promise.resolve({ ...USAGE_SUMMARY, threadId: input.threadId, totalTokens: 25_000 }),
      ),
    };
    const { rerender } = render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        threadId="thr_usage_a"
        usageReader={reader}
        onSend={vi.fn()}
      />,
    );
    await user.click(screen.getByRole("button", { name: "上下文用量详情" }));
    await waitFor(() => expect(reader.read).toHaveBeenCalledWith({ threadId: "thr_usage_a" }));

    rerender(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        threadId="thr_usage_b"
        usageReader={reader}
        onSend={vi.fn()}
      />,
    );
    await user.click(screen.getByRole("button", { name: "上下文用量详情" }));
    expect(await screen.findByText("25k")).toBeVisible();
    resolveFirst({ ...USAGE_SUMMARY, threadId: "thr_usage_a" });
    await waitFor(() => expect(screen.getByText("25k")).toBeVisible());
    expect(screen.queryByText("22k")).toBeNull();
  });

  /** 临近窗口上限时只改变视觉风险级别，仍不升级为打断输入的弹层。 */
  it("以危险态显示接近上限的最近请求", () => {
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        contextUsage={{
          certainty: "known",
          usedTokens: 184_000,
          limitTokens: 200_000,
          percentage: 92,
          ringPercentage: 92,
          tone: "danger",
          source: "provider",
          measuredAt: "2026-09-22T00:00:00Z",
        }}
        onSend={vi.fn()}
      />,
    );

    const indicator = screen.getByRole("progressbar", { name: "上下文使用量" });
    expect(indicator).toHaveAttribute("data-tone", "danger");
    expect(indicator).toHaveAttribute("aria-valuenow", "92");
  });

  /** 右键队列项复用 revision CAS 回调，且嵌套附件只获得自己的预览与移除动作。 */
  it("为排队消息和其中的附件提供对象级右键菜单", async () => {
    const user = userEvent.setup();
    const prioritize = vi.fn();
    const update = vi.fn();
    const remove = vi.fn();
    const openPreview = vi.fn();
    render(
      <ControlledComposerHarness
        activeTurn
        preferences={PREFERENCES}
        models={MODELS}
        queuedInputs={[
          {
            inputId: "input_context_menu",
            content: [
              { type: "attachment", attachmentId: "att_context_menu" },
              { type: "text", text: "排队中的文本" },
            ],
            attachments: [
              {
                attachmentId: "att_context_menu",
                displayName: "排队说明.txt",
                sizeBytes: 64,
                mediaKind: "text",
                mediaType: "text/plain",
              },
            ],
            kind: "follow_up",
            status: "pending",
            issue: null,
            inputRevision: 8,
            createdAt: "2026-09-23T00:00:00Z",
          },
        ]}
        onPrioritizeQueuedInput={prioritize}
        onUpdateQueuedInput={update}
        onDeleteQueuedInput={remove}
        onOpenQueuedAttachmentPreview={openPreview}
        onSend={vi.fn()}
      />,
    );

    const queue = screen.getByRole("list", { name: "排队消息" });
    const row = queue.querySelector<HTMLElement>(".ja-composer-queue__item");
    expect(row).not.toBeNull();
    if (row === null) return;
    const attachment = queue.querySelector<HTMLElement>(".ja-composer-queue-attachments > li");
    expect(attachment).not.toBeNull();
    if (attachment === null) return;

    fireEvent.contextMenu(attachment, { clientX: 20, clientY: 30 });
    const attachmentMenu = await screen.findByRole("menu", {
      name: "队列附件操作：排队说明.txt",
    });
    expect(within(attachmentMenu).getByRole("menuitem", { name: "预览附件" })).toBeVisible();
    expect(within(attachmentMenu).getByRole("menuitem", { name: "移除附件" })).toHaveClass(
      "is-danger",
    );
    expect(within(attachmentMenu).queryByRole("menuitem", { name: "调整方向" })).toBeNull();
    await user.click(within(attachmentMenu).getByRole("menuitem", { name: "预览附件" }));
    expect(openPreview).toHaveBeenCalledWith(
      {
        attachmentId: "att_context_menu",
        fileName: "排队说明.txt",
        sizeBytes: 64,
        mediaKind: "text",
        mediaType: "text/plain",
      },
      expect.any(HTMLButtonElement),
    );

    fireEvent.contextMenu(row, { clientX: 35, clientY: 45 });
    const queueMenu = await screen.findByRole("menu", { name: /排队消息操作/ });
    expect(within(queueMenu).getByRole("menuitem", { name: "调整方向" })).toBeVisible();
    expect(within(queueMenu).getByRole("menuitem", { name: "编辑消息" })).toBeVisible();
    expect(within(queueMenu).getByRole("menuitem", { name: "删除消息" })).toHaveClass("is-danger");
    await user.click(within(queueMenu).getByRole("menuitem", { name: "调整方向" }));
    expect(prioritize).toHaveBeenCalledWith("input_context_menu", 8);

    fireEvent.contextMenu(row, { clientX: 40, clientY: 55 });
    await user.click(await screen.findByRole("menuitem", { name: "编辑消息" }));
    expect(screen.getByRole("textbox", { name: /编辑第 1 条消息/ })).toBeVisible();
    await user.keyboard("{Escape}");
    expect(update).not.toHaveBeenCalled();

    fireEvent.contextMenu(row, { clientX: 45, clientY: 65 });
    await user.click(await screen.findByRole("menuitem", { name: "删除消息" }));
    await waitFor(() => expect(remove).toHaveBeenCalledWith("input_context_menu", 8));
  });

  /** 待发送附件按真实媒体能力显示预览，忙碌与不可预览附件不会暴露伪操作。 */
  it("为待发送附件显示可用预览与移除菜单并支持键盘焦点回归", async () => {
    const user = userEvent.setup();
    const openPreview = vi.fn();
    const remove = vi.fn();
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        attachmentDraftItems={[
          {
            state: "ready",
            itemId: "item_menu_image",
            attachmentId: "att_menu_image",
            fileName: "菜单图片.png",
            sizeBytes: 128,
            mediaKind: "image",
            mediaType: "image/png",
          },
          {
            state: "ready",
            itemId: "item_menu_binary",
            attachmentId: "att_menu_binary",
            fileName: "菜单归档.zip",
            sizeBytes: 256,
            mediaKind: "binary",
          },
        ]}
        onOpenAttachmentPreview={openPreview}
        onRemoveAttachment={remove}
        onSend={vi.fn()}
      />,
    );

    const list = screen.getByRole("list", { name: "待发送附件" });
    const image = list.querySelector<HTMLElement>('li[data-media-kind="image"]');
    const binary = list.querySelector<HTMLElement>('li[data-media-kind="binary"]');
    expect(image).not.toBeNull();
    expect(binary).not.toBeNull();
    if (image === null || binary === null) return;
    fireEvent.contextMenu(image, { clientX: 25, clientY: 35 });
    const imageMenu = await screen.findByRole("menu", { name: "附件操作：菜单图片.png" });
    expect(within(imageMenu).getByRole("menuitem", { name: "预览附件" })).toBeVisible();
    await user.click(within(imageMenu).getByRole("menuitem", { name: "预览附件" }));
    expect(openPreview).toHaveBeenCalledWith(
      expect.objectContaining({ attachmentId: "att_menu_image", fileName: "菜单图片.png" }),
      expect.any(HTMLButtonElement),
    );

    const binaryRemoveButton = screen.getByRole("button", {
      name: "移除附件 菜单归档.zip",
    });
    binaryRemoveButton.focus();
    fireEvent.keyDown(binaryRemoveButton, { key: "ContextMenu" });
    const binaryMenu = await screen.findByRole("menu", { name: "附件操作：菜单归档.zip" });
    expect(within(binaryMenu).queryByRole("menuitem", { name: "预览附件" })).toBeNull();
    const removeItem = within(binaryMenu).getByRole("menuitem", { name: "移除附件" });
    expect(removeItem).toBeEnabled();
    expect(removeItem).toHaveClass("is-danger");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(binaryRemoveButton).toHaveFocus());

    fireEvent.contextMenu(binary, { clientX: 30, clientY: 45 });
    await user.click(await screen.findByRole("menuitem", { name: "移除附件" }));
    expect(remove).toHaveBeenCalledWith("item_menu_binary");
  });

  /** 引用菜单只通过已注入的打开/移除回调行动，且 Preview 焦点来源是当前可见按钮。 */
  it("为可打开的 Composer 引用提供打开与移除菜单", async () => {
    const user = userEvent.setup();
    const openReference = vi.fn();
    const removeReference = vi.fn();
    const reference: ConversationContextReference = {
      type: "workspace_reference",
      workspaceId: "ws_context_menu",
      relativePath: "src/App.tsx",
      kind: "file",
    };
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        models={MODELS}
        contextReferences={[reference]}
        onOpenWorkspaceReference={openReference}
        onContextReferencesChange={removeReference}
        onSend={vi.fn()}
      />,
    );

    const chip = screen
      .getByRole("list", { name: "消息上下文" })
      .querySelector<HTMLElement>(".ja-composer-context__chip");
    expect(chip).not.toBeNull();
    if (chip === null) return;
    fireEvent.contextMenu(chip, { clientX: 20, clientY: 30 });
    const menu = await screen.findByRole("menu", { name: "引用操作" });
    expect(within(menu).getByRole("menuitem", { name: "打开文件" })).toBeVisible();
    expect(within(menu).getByRole("menuitem", { name: "移除引用" })).toBeVisible();
    await user.click(within(menu).getByRole("menuitem", { name: "打开文件" }));
    expect(openReference).toHaveBeenCalledWith(reference, expect.any(HTMLButtonElement));

    fireEvent.contextMenu(chip, { clientX: 25, clientY: 35 });
    await user.click(await screen.findByRole("menuitem", { name: "移除引用" }));
    expect(removeReference).toHaveBeenCalledWith([]);
  });
});
