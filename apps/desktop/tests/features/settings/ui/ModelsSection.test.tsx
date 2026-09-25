// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ModelsSection } from "@/features/settings/ui/models";
import type { SettingsPorts } from "@/features/settings/application/ports";
import type { ProviderProjection } from "@/features/settings/domain/types";

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
      capabilities: { contextWindowTokens: 256_000, maxOutputTokens: 32_000 },
      reasoningLevelMap: { low: "low", high: "high" },
      defaultReasoningLevel: "high",
    },
    {
      modelId: "model_secondary",
      name: "Secondary",
      model: "gpt-5.6-mini",
      capabilities: { contextWindowTokens: 128_000, maxOutputTokens: 8_192 },
      reasoningLevelMap: {},
      defaultReasoningLevel: null,
    },
  ],
};

/** 模型区测试端口保持真实聚合接口形状；测试只断言本轮触发的副作用。 */
function renderModels(
  overrides: Partial<SettingsPorts> = {},
  focusRequest?: { providerId: string; modelId?: string; requestId: number },
): void {
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
  render(
    <ModelsSection
      providers={[PROVIDER]}
      defaultSelection={{
        providerId: PROVIDER.providerId,
        modelId: "model_primary",
        reasoningLevel: "high",
      }}
      snapshotRevision={1}
      focusRequest={focusRequest}
      ports={ports}
    />,
  );
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

  /** 上游目录只改变编辑草稿：已有行保持原样，新模型等待用户明确保存后才进入配置。 */
  it("imports only new upstream models into the saved provider draft", async () => {
    const user = userEvent.setup();
    const onDiscoverModels = vi.fn(async () => ({
      items: ["gpt-5.6-sol", "gpt-5.6-new"],
      truncated: false,
    }));
    const onSaveProvider = vi.fn(async () => undefined);
    renderModels({ onDiscoverModels, onSaveProvider });

    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    await user.click(within(dialog).getByRole("button", { name: "从上游获取" }));

    await waitFor(() => expect(onDiscoverModels).toHaveBeenCalledWith("provider_openai"));
    const upstreamIds = within(dialog)
      .getAllByPlaceholderText("例如：gpt-5.6-sol")
      .map((input) => (input as HTMLInputElement).value);
    expect(upstreamIds).toEqual(["gpt-5.6-sol", "gpt-5.6-mini", "gpt-5.6-new"]);
    expect(within(dialog).getByRole("status")).toHaveTextContent(
      "已添加 1 个上游模型，保存后生效。",
    );
    expect(onSaveProvider).not.toHaveBeenCalled();
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
