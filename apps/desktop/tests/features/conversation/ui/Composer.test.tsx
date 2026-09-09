// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  Composer,
  type ComposerProps,
  type ComposerSubmit,
} from "@/features/conversation/ui/composer/Composer";
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

interface ControlledComposerHarnessProps extends Omit<ComposerProps, "text" | "onTextChange"> {
  initialText?: string;
  initialContextReferences?: readonly ConversationContextReference[];
}

/** 模拟真实会话层持有草稿，测试不在 Composer 内重新引入第二份 state。 */
function ControlledComposerHarness({
  initialText = "",
  initialContextReferences = [],
  ...composerProps
}: ControlledComposerHarnessProps): ReactElement {
  const [text, setText] = useState(initialText);
  const [contextReferences, setContextReferences] = useState(initialContextReferences);
  return (
    <Composer
      {...composerProps}
      text={text}
      onTextChange={setText}
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

  it("将计划或目标入口固定在访问权限右侧", () => {
    render(
      <ControlledComposerHarness
        preferences={PREFERENCES}
        modeStatus={<button type="button">目标</button>}
        onSend={vi.fn()}
      />,
    );

    const accessMode = screen.getByRole("combobox", { name: "访问模式" });
    const goalEntry = screen.getByRole("button", { name: "目标" });
    expect(
      accessMode.compareDocumentPosition(goalEntry) & Node.DOCUMENT_POSITION_FOLLOWING,
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
    expect(screen.getByRole("presentation")).toHaveAttribute("src", "ja-attachment://thumb_image");
    expect(screen.getByText("尚未生成缩略图").closest("li")).not.toHaveAttribute(
      "data-has-thumbnail",
    );
    expect(screen.getByRole("presentation").closest("li")).toHaveAttribute(
      "data-has-thumbnail",
      "true",
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

  /** Goal/Plan 命令在“添加”分组展示人性化主标签，必填 Goal 参数仍在主输入框内原位收集。 */
  it("分组展示 Goal/Plan 命令并用主输入框完成空 Goal 参数", async () => {
    const user = userEvent.setup();
    const plan = vi.fn();
    const goal = vi.fn();
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
          {
            id: "goal",
            name: "goal",
            aliases: ["目标"],
            group: "添加",
            icon: "goal",
            label: "目标",
            description: "设置要持续追求的目标",
            available: true,
            argument: { mode: "required", label: "目标", placeholder: "描述目标" },
            execute: goal,
          },
        ]}
        onSend={vi.fn()}
      />,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    input.focus();
    const group = screen.getByRole("group", { name: "添加" });
    expect(within(group).getAllByRole("option")).toHaveLength(2);
    expect(
      within(group).getByRole("option", { name: /计划.*先制定计划再决定是否执行/u }),
    ).toBeVisible();
    expect(
      within(group).getByRole("option", { name: /目标.*设置要持续追求的目标/u }),
    ).toBeVisible();

    await user.keyboard("{ArrowDown}{Enter}");
    const goalInput = screen.getByRole("textbox", { name: "描述目标" });
    expect(goalInput).toHaveAttribute("placeholder", "描述目标");
    expect(screen.getByRole("button", { name: "创建目标" })).toBeVisible();
    await user.type(goalInput, "完成稳定验收");
    await user.keyboard("{Escape}");
    expect(screen.getByRole("textbox", { name: "消息" })).toHaveValue("完成稳定验收");
    expect(screen.getByRole("textbox", { name: "消息" })).toHaveFocus();
    expect(plan).not.toHaveBeenCalled();
    expect(goal).not.toHaveBeenCalled();
  });

  /** 直接 Slash 调用只把结构化参数交给动作，命令正文不得作为普通消息发送。 */
  it("直接执行 /plan off 与带目标正文的 /goal", async () => {
    const user = userEvent.setup();
    const plan = vi.fn();
    const goal = vi.fn();
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
        id: "goal",
        name: "goal",
        aliases: ["目标"],
        label: "创建目标",
        description: "创建目标",
        available: true,
        argument: { mode: "required", label: "目标", placeholder: "描述目标" },
        execute: goal,
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
        initialText="/goal 完成生产验收"
        slashCommands={commands}
        onSend={submit}
      />,
    );
    const goalInput = screen.getByRole("textbox", { name: "消息" });
    goalInput.focus();
    await user.keyboard("{Enter}");
    await waitFor(() => expect(goal).toHaveBeenCalledWith({ argument: "完成生产验收" }));
    expect(goalInput).toHaveValue("");
    expect(submit).not.toHaveBeenCalled();
  });

  /** 异步 Goal 创建失败恢复同一内联编辑态、正文与选区，避免用户重新输入目标。 */
  it("Goal 内联命令失败后恢复正文和输入焦点", async () => {
    const user = userEvent.setup();
    render(
      <ControlledComposerHarness
        initialText="/goal"
        slashCommands={[
          {
            id: "goal",
            name: "goal",
            aliases: ["目标"],
            label: "创建目标",
            description: "创建目标",
            available: true,
            argument: { mode: "required", label: "目标", placeholder: "描述目标" },
            execute: () => Promise.reject(new Error("private goal failure")),
          },
        ]}
        onSend={vi.fn()}
      />,
    );
    const input = screen.getByRole("textbox", { name: "消息" });
    input.focus();
    await user.keyboard("{Enter}");
    const goalInput = screen.getByRole("textbox", { name: "描述目标" });
    await user.type(goalInput, "保留这段目标");
    await user.keyboard("{Enter}");

    await waitFor(() => expect(screen.getByRole("textbox", { name: "描述目标" })).toHaveFocus());
    expect(screen.getByRole("textbox", { name: "描述目标" })).toHaveValue("保留这段目标");
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

    expect(screen.getByText("运行被中断")).toBeVisible();
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
          certainty: "known",
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
          certainty: "known",
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
