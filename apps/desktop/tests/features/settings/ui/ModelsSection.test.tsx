// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import userEvent from "@testing-library/user-event";
import type { ComponentProps } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ModelsSection } from "@/features/settings/ui/models";
import type { SettingsPorts } from "@/features/settings/application/ports";
import type { ProviderSave } from "@/shared/settings/types";
import type {
  ProjectProviderRuntimeOverride,
  ProviderProjection,
} from "@/features/settings/domain/types";

afterEach(cleanup);

const PROVIDER: ProviderProjection = {
  providerId: "provider_openai",
  name: "OpenAI",
  api: "openai_responses",
  baseUrl: "https://api.openai.com/v1",
  credentialId: "cred_openai",
  credentialConfigured: true,
  networkTimeouts: { connectTimeoutMs: 10_000, requestTimeoutMs: 120_000 },
  agentDefaults: {
    context: { autoCompact: true },
  },
  models: [
    {
      modelId: "model_primary",
      name: "Primary",
      model: "gpt-5.6-sol",
      capabilities: {
        contextWindowTokens: 256_000,
        maxOutputTokens: 32_000,
        supportsOutputSchema: false,
      },
      reasoningLevelMap: { low: "low", high: "high" },
      defaultReasoningLevel: "high",
    },
    {
      modelId: "model_secondary",
      name: "Secondary",
      model: "gpt-5.6-mini",
      capabilities: {
        contextWindowTokens: 128_000,
        maxOutputTokens: 8_192,
        supportsOutputSchema: false,
      },
      reasoningLevelMap: {},
      defaultReasoningLevel: null,
    },
  ],
};

/** 测试端口保留真实聚合形状，并暴露父级 rerender 验证权威投影刷新，避免只测固定快照。 */
function renderModels(
  overrides: Partial<SettingsPorts> = {},
  focusRequest?: { providerId: string; modelId?: string; requestId: number },
  projectCatalog?: {
    providers: ProviderProjection[];
    overrides: ProjectProviderRuntimeOverride[];
  },
): {
  props: ComponentProps<typeof ModelsSection>;
  view: ReturnType<typeof render>;
} {
  const ports: SettingsPorts = {
    onCreateProvider: vi.fn(async () => undefined),
    onSaveProvider: vi.fn(async () => undefined),
    onDeleteProvider: vi.fn(async () => undefined),
    onMoveProvider: vi.fn(async () => undefined),
    onSaveModel: vi.fn(async () => undefined),
    onTestModel: vi.fn(async () => ({ responseModel: "fixture", latencyMs: 1 })),
    onDiscoverModels: vi.fn(async () => ({ items: [], truncated: false })),
    onDeleteModel: vi.fn(async () => undefined),
    onMoveModel: vi.fn(async () => undefined),
    onDefaultSelectionChange: vi.fn(async () => undefined),
    onSubagentSettingsChange: vi.fn(async () => undefined),
    onReplaceCredential: vi.fn(async () => undefined),
    onClearCredential: vi.fn(async () => undefined),
    onRevealProviderCredential: vi.fn(async () => "fixture-only"),
    onSaveMcp: vi.fn(async () => undefined),
    onDeleteMcp: vi.fn(async () => undefined),
    onTestMcp: vi.fn(async () => "connected" as const),
    onToggleSkill: vi.fn(async () => undefined),
    onAccessModeChange: vi.fn(async () => undefined),
    onClarificationEnabledChange: vi.fn(async () => undefined),
    onAppearanceChange: vi.fn(async () => undefined),
    ...overrides,
  };
  const props: ComponentProps<typeof ModelsSection> = {
    providers: projectCatalog?.providers ?? [PROVIDER],
    globalProviders: [PROVIDER],
    defaultSelection: {
      providerId: PROVIDER.providerId,
      modelId: "model_primary",
      reasoningLevel: "high",
    },
    snapshotRevision: 1,
    focusRequest,
    ports,
    projectScope: projectCatalog !== undefined,
    projectProviderOverrides: projectCatalog?.overrides ?? [],
  };
  return { props, view: render(<ModelsSection {...props} />) };
}

describe("ModelsSection", () => {
  /** 默认状态属于模型身份而非操作按钮，列表中只能有一个可见 badge。 */
  it("shows one default badge and keeps the real model id with context summary", () => {
    renderModels();

    expect(screen.getAllByText("当前默认")).toHaveLength(1);
    expect(screen.getByText("gpt-5.6-sol")).toBeVisible();
    expect(screen.getByText("gpt-5.6-mini")).toBeVisible();
    expect(screen.getByText("上下文 128,000 tokens")).toBeVisible();
    expect(screen.queryByRole("button", { name: "当前默认" })).toBeNull();
  });

  /** 项目隐藏模型提供逐项恢复入口，且恢复只影响同一 Provider/Model 稳定身份。 */
  it("restores one hidden global model from the project catalog", async () => {
    const user = userEvent.setup();
    const onRestoreProjectModel = vi.fn(async () => undefined);
    const projectProvider = { ...PROVIDER, models: [PROVIDER.models[0]!] };
    renderModels({ onRestoreProjectModel }, undefined, {
      providers: [projectProvider],
      overrides: [{ providerId: PROVIDER.providerId, removedModelIds: ["model_secondary"] }],
    });

    const hiddenCatalog = screen.getByRole("region", { name: "OpenAI 项目隐藏模型" });
    expect(within(hiddenCatalog).getByText("gpt-5.6-mini")).toBeVisible();
    await user.click(within(hiddenCatalog).getByRole("button", { name: "恢复模型" }));
    expect(onRestoreProjectModel).toHaveBeenCalledWith("provider_openai", "model_secondary");
  });

  /** 排序是低频对象动作，必须通过菜单触发真实方向与稳定模型 ID。 */
  it("moves a model through its more-actions menu", async () => {
    const user = userEvent.setup();
    const onMoveModel = vi.fn(async () => undefined);
    renderModels({ onMoveModel });

    const row = screen.getByText("gpt-5.6-mini").closest("article");
    expect(row).not.toBeNull();
    await user.click(within(row!).getByRole("button", { name: /更多操作/ }));
    await user.click(screen.getByRole("menuitem", { name: "上移" }));

    await waitFor(() =>
      expect(onMoveModel).toHaveBeenCalledWith("provider_openai", "model_secondary", -1),
    );
  });

  /** 删除先可取消且不触发端口，确认后才提交非默认模型删除。 */
  it("requires confirmation before deleting a model and supports cancellation", async () => {
    const user = userEvent.setup();
    const onDeleteModel = vi.fn(async () => undefined);
    renderModels({ onDeleteModel });

    const row = screen.getByText("gpt-5.6-mini").closest("article");
    await user.click(within(row!).getByRole("button", { name: /更多操作/ }));
    await user.click(screen.getByRole("menuitem", { name: "删除" }));
    const dialog = screen.getByRole("alertdialog", { name: "删除 Secondary？" });
    await user.click(within(dialog).getByRole("button", { name: "取消" }));
    expect(onDeleteModel).not.toHaveBeenCalled();
    expect(screen.queryByRole("alertdialog", { name: "删除 Secondary？" })).toBeNull();
    expect(within(row!).getByRole("button", { name: /更多操作/ })).toHaveFocus();

    await user.click(within(row!).getByRole("button", { name: /更多操作/ }));
    await user.click(screen.getByRole("menuitem", { name: "删除" }));
    await user.click(
      within(screen.getByRole("alertdialog", { name: "删除 Secondary？" })).getByRole("button", {
        name: "删除模型",
      }),
    );
    await waitFor(() =>
      expect(onDeleteModel).toHaveBeenCalledWith("provider_openai", "model_secondary", null),
    );
  });

  /** 模型摘要是编辑的就地入口，进入 sheet 后应保留真实 upstream 标识。 */
  it("opens the provider editor from the model edit entry", async () => {
    const user = userEvent.setup();
    renderModels();

    await user.click(screen.getByRole("button", { name: /编辑模型 gpt-5\.6-sol/ }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    const upstream = within(dialog).getByDisplayValue("gpt-5.6-sol");
    expect(upstream).toHaveValue("gpt-5.6-sol");
    expect(upstream).toHaveFocus();
  });

  /** 结构化终答声明随聚合配置保存，项目编辑仍只通过当前 scope 的保存端口提交。 */
  it.each([
    {
      scope: "全局",
      editButton: /编辑模型 gpt-5\.6-sol/u,
      saveButton: "保存更改",
      projectCatalog: undefined,
    },
    {
      scope: "项目",
      editButton: /编辑项目模型 gpt-5\.6-sol/u,
      saveButton: "保存项目模型",
      projectCatalog: { providers: [PROVIDER], overrides: [] },
    },
  ])("在$scope模型范围保存结构化终答能力", async ({ editButton, saveButton, projectCatalog }) => {
    const user = userEvent.setup();
    const onSaveProvider = vi.fn(async () => undefined);
    renderModels({ onSaveProvider }, undefined, projectCatalog);

    await user.click(screen.getByRole("button", { name: editButton }));
    const dialog = screen.getByRole("dialog");
    const primaryModel = dialog.querySelector<HTMLElement>('[data-model-id="model_primary"]');
    expect(primaryModel).not.toBeNull();
    await user.click(within(primaryModel!).getByText("高级能力"));
    const capability = within(primaryModel!).getByRole("checkbox", { name: "支持结构化终答" });
    expect(capability).not.toBeChecked();
    await user.click(capability);
    await user.click(within(dialog).getByRole("button", { name: saveButton }));

    await waitFor(() => expect(onSaveProvider).toHaveBeenCalledOnce());
    expect(onSaveProvider).toHaveBeenCalledWith(
      expect.objectContaining({
        models: expect.arrayContaining([
          expect.objectContaining({
            modelId: "model_primary",
            capabilities: expect.objectContaining({ supportsOutputSchema: true }),
          }),
        ]),
      }),
    );
  });

  /** Provider 编辑框始终保留一个 API Key 输入，不再使用已配置、替换或删除凭据的分支样式。 */
  it("shows the recalled API Key in one toggleable input", async () => {
    const user = userEvent.setup();
    const onRevealProviderCredential = vi.fn(async () => "fixture-only");
    renderModels({ onRevealProviderCredential });

    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    const apiKey = await within(dialog).findByDisplayValue("fixture-only");
    expect(apiKey).toHaveAttribute("type", "password");
    expect(within(dialog).queryByText("凭据已配置")).toBeNull();
    expect(within(dialog).queryByText("替换凭据")).toBeNull();
    expect(within(dialog).queryByText("删除凭据")).toBeNull();
    await user.click(within(dialog).getByRole("button", { name: "显示 API Key" }));
    expect(apiKey).toHaveAttribute("type", "text");
    expect(onRevealProviderCredential).toHaveBeenCalledWith("provider_openai");
  });

  /** 配置提交会刷新父投影；凭据失败仍须保留编辑 Secret，重试不能重复保存配置或回显旧值。 */
  it("preserves an edited secret after parent projection refresh and retries only credentials", async () => {
    const user = userEvent.setup();
    let persistedSecret = "saved-secret";
    const onRevealProviderCredential = vi.fn(async () => persistedSecret);
    const onReplaceCredential = vi
      .fn<SettingsPorts["onReplaceCredential"]>()
      .mockRejectedValueOnce(new Error("vault unavailable"))
      .mockImplementationOnce(async (_credentialId, secret) => {
        persistedSecret = secret;
      });
    /** 与真实配置 Query 相同，保存成功先发布新的 Provider 和端口对象，再开始凭据提交。 */
    const onSaveProvider = vi.fn(async (provider: ProviderSave) => {
      view.rerender(
        <ModelsSection
          {...props}
          providers={[{ ...provider, credentialConfigured: true }]}
          snapshotRevision={2}
          ports={{ ...props.ports }}
        />,
      );
    });
    const { props, view } = renderModels({
      onRevealProviderCredential,
      onReplaceCredential,
      onSaveProvider,
    });

    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    const secretInput = await within(dialog).findByDisplayValue("saved-secret");
    await user.clear(within(dialog).getByLabelText("Base URL"));
    await user.type(within(dialog).getByLabelText("Base URL"), "https://gateway.example.test/v1");
    await user.clear(secretInput);
    await user.type(secretInput, "replacement-secret");
    await user.click(within(dialog).getByRole("button", { name: "保存更改" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "供应商配置已保存，密钥操作未完成。输入已保留，可再次保存重试。",
    );
    expect(secretInput).toHaveValue("replacement-secret");
    expect(onRevealProviderCredential).toHaveBeenCalledTimes(1);
    expect(onSaveProvider).toHaveBeenCalledTimes(1);
    expect(onReplaceCredential).toHaveBeenLastCalledWith("cred_openai", "replacement-secret");
    await user.click(within(dialog).getByRole("button", { name: "保存更改" }));

    await waitFor(() => expect(screen.queryByRole("dialog", { name: "编辑供应商" })).toBeNull());
    expect(onSaveProvider).toHaveBeenCalledTimes(1);
    expect(onReplaceCredential).toHaveBeenCalledTimes(2);
    expect(onReplaceCredential).toHaveBeenLastCalledWith("cred_openai", "replacement-secret");
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    expect(
      await within(screen.getByRole("dialog", { name: "编辑供应商" })).findByDisplayValue(
        "replacement-secret",
      ),
    ).toBeVisible();
    expect(onRevealProviderCredential).toHaveBeenCalledTimes(2);
  });

  /** 端口刷新不重置同一编辑会话；作用域切换必须重新初始化并使用最新真实回显能力。 */
  it("retains the secret across port refresh and recalls it again after scope changes", async () => {
    const user = userEvent.setup();
    const initialReveal = vi.fn(async () => "initial-saved-secret");
    const latestReveal = vi.fn(async () => "latest-saved-secret");
    const { props, view } = renderModels({ onRevealProviderCredential: initialReveal });
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    const secretInput = await within(dialog).findByDisplayValue("initial-saved-secret");
    await user.clear(secretInput);
    await user.type(secretInput, "unsaved-secret");
    const latestPorts = { ...props.ports, onRevealProviderCredential: latestReveal };
    view.rerender(<ModelsSection {...props} ports={latestPorts} snapshotRevision={2} />);

    expect(secretInput).toHaveValue("unsaved-secret");
    expect(initialReveal).toHaveBeenCalledTimes(1);
    expect(latestReveal).not.toHaveBeenCalled();
    view.rerender(<ModelsSection {...props} ports={latestPorts} projectScope />);
    expect(
      within(screen.getByRole("dialog", { name: "编辑项目模型" })).queryByLabelText("API Key"),
    ).toBeNull();
    view.rerender(<ModelsSection {...props} ports={latestPorts} projectScope={false} />);

    expect(
      await within(screen.getByRole("dialog", { name: "编辑供应商" })).findByDisplayValue(
        "latest-saved-secret",
      ),
    ).toBeVisible();
    expect(latestReveal).toHaveBeenCalledTimes(1);
  });

  /** Sheet 关闭即失去旧读取资格；新 Provider 的回显和关闭重开均不能接收旧 Promise。 */
  it("discards a late credential read after closing and opening another provider", async () => {
    const user = userEvent.setup();
    let completeOldRead!: (secret: string) => void;
    const oldRead = new Promise<string>((resolve) => {
      completeOldRead = resolve;
    });
    const otherProvider = {
      ...PROVIDER,
      providerId: "provider_other",
      credentialId: "cred_other",
      name: "Other provider",
    };
    const onRevealProviderCredential = vi.fn(async (providerId: string) =>
      providerId === PROVIDER.providerId ? oldRead : "other-saved-secret",
    );
    const { props, view } = renderModels({ onRevealProviderCredential });
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    await waitFor(() => expect(onRevealProviderCredential).toHaveBeenCalledTimes(1));
    await user.click(
      within(screen.getByRole("dialog", { name: "编辑供应商" })).getByRole("button", {
        name: "关闭供应商编辑",
      }),
    );
    view.rerender(<ModelsSection {...props} providers={[PROVIDER, otherProvider]} />);
    await user.click(screen.getByRole("button", { name: "Other provider 2 个模型" }));
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    const secretInput = await within(dialog).findByDisplayValue("other-saved-secret");
    await user.clear(secretInput);
    await user.type(secretInput, "other-draft-secret");
    completeOldRead("obsolete-secret");
    await oldRead;
    expect(secretInput).toHaveValue("other-draft-secret");
    await user.click(within(dialog).getByRole("button", { name: "关闭供应商编辑" }));
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    expect(
      await within(screen.getByRole("dialog", { name: "编辑供应商" })).findByDisplayValue(
        "other-saved-secret",
      ),
    ).toBeVisible();
    expect(onRevealProviderCredential.mock.calls.map(([providerId]) => providerId)).toEqual([
      "provider_openai",
      "provider_other",
      "provider_other",
    ]);
  });

  /** 发现结果先由用户挑选，确认后也只进入本地草稿，避免目录请求替用户保存配置。 */
  it("adds only confirmed upstream models to the provider draft", async () => {
    const user = userEvent.setup();
    const onDiscoverModels = vi.fn(async () => ({
      items: ["gpt-5.6-sol", "gpt-5.6-new", "gpt-5.6-other"],
      truncated: false,
    }));
    const savedProviders: ProviderSave[] = [];
    const onSaveProvider = vi.fn(async (provider: ProviderSave) => {
      savedProviders.push(provider);
    });
    renderModels({ onDiscoverModels, onSaveProvider });

    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    await user.click(within(dialog).getByRole("button", { name: "从上游获取" }));

    await waitFor(() => expect(onDiscoverModels).toHaveBeenCalledWith("provider_openai"));
    const picker = await screen.findByRole("dialog", { name: "选择要添加的模型" });
    expect(within(picker).queryByRole("checkbox", { name: "gpt-5.6-sol" })).toBeNull();
    expect(within(picker).getByRole("checkbox", { name: "gpt-5.6-new" })).toBeChecked();
    expect(within(picker).getByRole("checkbox", { name: "gpt-5.6-other" })).toBeChecked();
    expect(within(dialog).getAllByPlaceholderText("例如：gpt-5.6-sol")).toHaveLength(2);
    await user.click(within(picker).getByRole("checkbox", { name: "gpt-5.6-other" }));
    await user.click(within(picker).getByRole("button", { name: "添加所选" }));

    const upstreamIds = within(dialog)
      .getAllByPlaceholderText("例如：gpt-5.6-sol")
      .map((input) => (input as HTMLInputElement).value);
    expect(upstreamIds).toEqual(["gpt-5.6-sol", "gpt-5.6-mini", "gpt-5.6-new"]);
    expect(within(dialog).getAllByLabelText("单次最大输出 Tokens")[2]).toHaveValue(16_384);
    expect(within(dialog).getByRole("status")).toHaveTextContent(
      "已添加 1 个上游模型，保存后生效。",
    );
    expect(onSaveProvider).not.toHaveBeenCalled();
    await waitFor(() => expect(within(dialog).getAllByLabelText("上游模型标识")[2]).toHaveFocus());
    await user.click(within(dialog).getByRole("button", { name: "保存更改" }));
    await waitFor(() => expect(onSaveProvider).toHaveBeenCalledTimes(1));
    const saved = savedProviders[0];
    expect(saved?.models.map((model) => model.model)).toEqual([
      "gpt-5.6-sol",
      "gpt-5.6-mini",
      "gpt-5.6-new",
    ]);
  });

  /** 筛选仅改变可见项，批量操作不能清掉用户在其它搜索结果里的选择。 */
  it("keeps hidden selections while toggling the filtered results", async () => {
    const user = userEvent.setup();
    renderModels({
      onDiscoverModels: vi.fn(async () => ({
        items: ["gpt-alpha", "gpt-beta", "gpt-gamma"],
        truncated: true,
      })),
    });
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    await user.click(screen.getByRole("button", { name: "从上游获取" }));
    const picker = await screen.findByRole("dialog", { name: "选择要添加的模型" });
    expect(within(picker).getByRole("status")).toHaveTextContent("已选 3 个 · 仅显示前 200 项");
    await user.type(within(picker).getByRole("searchbox", { name: "搜索模型" }), "beta");
    await user.click(within(picker).getByRole("button", { name: "取消全选" }));
    expect(within(picker).getByRole("status")).toHaveTextContent("已选 2 个");
    await user.clear(within(picker).getByRole("searchbox", { name: "搜索模型" }));
    expect(within(picker).getByRole("checkbox", { name: "gpt-alpha" })).toBeChecked();
    expect(within(picker).getByRole("checkbox", { name: "gpt-beta" })).not.toBeChecked();
    await user.click(within(picker).getByRole("button", { name: "全选" }));
    expect(within(picker).getByRole("status")).toHaveTextContent("已选 3 个");
    await user.click(within(picker).getByRole("button", { name: "取消全选" }));
    expect(within(picker).getByRole("button", { name: "添加所选" })).toBeDisabled();
    await user.type(within(picker).getByRole("searchbox", { name: "搜索模型" }), "missing");
    expect(within(picker).getByText("没有匹配的模型。")).toBeVisible();
  });

  /** 取消和 Escape 都只关闭目录，保留供应商编辑内容并恢复原触发点。 */
  it("discards selection on cancel and Escape", async () => {
    const user = userEvent.setup();
    renderModels({
      onDiscoverModels: vi.fn(async () => ({ items: ["gpt-new"], truncated: false })),
    });
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    const discover = within(dialog).getByRole("button", { name: "从上游获取" });
    await user.click(discover);
    let picker = await screen.findByRole("dialog", { name: "选择要添加的模型" });
    await user.click(within(picker).getByRole("button", { name: "取消" }));
    await waitFor(() => expect(picker).not.toBeInTheDocument());
    expect(discover).toHaveFocus();
    expect(within(dialog).getAllByLabelText("上游模型标识")).toHaveLength(2);
    await user.click(discover);
    picker = await screen.findByRole("dialog", { name: "选择要添加的模型" });
    await user.keyboard("{Escape}");
    await waitFor(() => expect(picker).not.toBeInTheDocument());
    expect(discover).toHaveFocus();
    expect(within(dialog).getAllByLabelText("上游模型标识")).toHaveLength(2);
  });

  /** 空目录和全部重复都保持可关闭的结果状态，不产生可提交的空操作。 */
  it("shows empty discovery states without changing the draft", async () => {
    const user = userEvent.setup();
    const onDiscoverModels = vi
      .fn()
      .mockResolvedValueOnce({ items: [], truncated: false })
      .mockResolvedValueOnce({ items: ["gpt-5.6-sol", "gpt-5.6-mini"], truncated: false });
    renderModels({ onDiscoverModels });
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    const discover = within(dialog).getByRole("button", { name: "从上游获取" });
    await user.click(discover);
    let picker = await screen.findByRole("dialog", { name: "选择要添加的模型" });
    expect(within(picker).getByText("上游目录没有返回模型。")).toBeVisible();
    expect(within(picker).getByRole("button", { name: "添加所选" })).toBeDisabled();
    await user.click(within(picker).getByRole("button", { name: "取消" }));
    await user.click(discover);
    picker = await screen.findByRole("dialog", { name: "选择要添加的模型" });
    expect(within(picker).getByText("上游模型均已添加。")).toBeVisible();
    expect(within(dialog).getAllByLabelText("上游模型标识")).toHaveLength(2);
  });

  /** 获取失败保持当前 Sheet 和草稿，下一次点击可重新请求上游目录。 */
  it("keeps the editor open and retries discovery after failure", async () => {
    const user = userEvent.setup();
    const onDiscoverModels = vi
      .fn()
      .mockRejectedValueOnce(new Error("catalog unavailable"))
      .mockResolvedValueOnce({ items: ["gpt-new"], truncated: false });
    renderModels({ onDiscoverModels });
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    const discover = within(dialog).getByRole("button", { name: "从上游获取" });
    await user.click(discover);
    await waitFor(() =>
      expect(within(dialog).getByRole("alert")).toHaveTextContent(
        "获取上游模型失败，请检查已保存的连接信息",
      ),
    );
    expect(within(dialog).getAllByLabelText("上游模型标识")).toHaveLength(2);
    await user.click(discover);
    expect(await screen.findByRole("dialog", { name: "选择要添加的模型" })).toBeVisible();
    expect(onDiscoverModels).toHaveBeenCalledTimes(2);
  });

  /** 新 Provider 或修改过连接的草稿都不能调用目录，避免把未保存的连接误当作已生效。 */
  it("disables upstream discovery before a provider connection is saved", async () => {
    const user = userEvent.setup();
    renderModels();

    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    const dialog = screen.getByRole("dialog", { name: "新增供应商" });
    const discover = within(dialog).getByRole("button", { name: "从上游获取" });
    expect(discover).toBeDisabled();
    expect(discover).toHaveAttribute("title", "请先保存连接信息和 API Key 后再获取");
    expect(within(dialog).getByRole("status")).toHaveTextContent("保存供应商后可从上游获取模型。");
  });

  it("routes a settings search request to the selected model edit entry", async () => {
    renderModels({}, { providerId: PROVIDER.providerId, modelId: "model_secondary", requestId: 1 });

    await waitFor(() =>
      expect(screen.getByRole("button", { name: /编辑模型 gpt-5\.6-mini/ })).toHaveFocus(),
    );
  });

  it("routes a provider search request to the provider editor entry", async () => {
    renderModels({}, { providerId: PROVIDER.providerId, requestId: 2 });

    await waitFor(() => expect(screen.getByRole("button", { name: "编辑供应商" })).toHaveFocus());
  });
});
