// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { toast } from "sonner";
import { SubagentsSection } from "@/features/settings/ui/subagents";
import type { SettingsPorts } from "@/features/settings/application/ports";
import type { SettingsSnapshot } from "@/features/settings/domain/types";

afterEach(cleanup);

const PROVIDERS: SettingsSnapshot["providers"] = [
  {
    providerId: "provider_openai",
    name: "OpenAI",
    api: "openai_responses",
    baseUrl: "https://api.openai.com/v1",
    credentialId: "cred_openai",
    credentialConfigured: true,
    networkTimeouts: { connectTimeoutMs: 10_000, requestTimeoutMs: 120_000 },
    agentDefaults: {
      context: { autoCompact: true },
      turnLimits: { maxModelRounds: 32, maxToolCalls: 128, wallTimeoutMs: 3_600_000 },
    },
    models: [
      {
        modelId: "model_gpt",
        name: "GPT 5.6",
        model: "gpt-5.6-sol",
        capabilities: { contextWindowTokens: 256_000, maxOutputTokens: 32_000 },
        reasoningLevelMap: { high: "high" },
        defaultReasoningLevel: "high",
      },
    ],
  },
];

/** 使用真实控件和严格快照，测试不通过本地状态伪造服务端保存成功。 */
function renderSection(
  onChange: SettingsPorts["onSubagentSettingsChange"] = vi.fn(async () => undefined),
  settings: SettingsSnapshot["subagents"] = {
    enabled: true,
    providerId: null,
    modelId: null,
    reasoningLevel: null,
  },
  providers = PROVIDERS,
  onOpenModels = vi.fn(),
): void {
  render(
    <SubagentsSection
      settings={settings}
      providers={providers}
      onChange={onChange}
      onOpenModels={onOpenModels}
    />,
  );
}

describe("SubagentsSection", () => {
  it("uses the parent model by default and exposes real upstream ids grouped by provider", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn(async () => undefined);
    renderSection(onChange);

    expect(screen.getByRole("switch", { name: "启用子智能体" })).toHaveAttribute(
      "aria-checked",
      "true",
    );
    const modelSelect = screen.getByRole("combobox", { name: "子智能体模型" });
    expect(modelSelect).toHaveTextContent("跟随父任务");
    expect(modelSelect).toHaveAttribute("aria-describedby", "subagents-model-hint");
    await user.click(screen.getByRole("combobox", { name: "子智能体模型" }));
    expect(screen.getByRole("group", { name: "OpenAI" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: /gpt-5\.6-sol/ })).toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: /gpt-5\.6-sol/ }));
    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith({
        enabled: true,
        providerId: "provider_openai",
        modelId: "model_gpt",
        reasoningLevel: null,
      }),
    );
  });

  it("keeps the selected model while disabling the new-session capability", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn(async () => undefined);
    renderSection(onChange, {
      enabled: true,
      providerId: "provider_openai",
      modelId: "model_gpt",
      reasoningLevel: "high",
    });
    await user.click(screen.getByRole("switch", { name: "启用子智能体" }));
    expect(onChange).toHaveBeenCalledWith({
      enabled: false,
      providerId: "provider_openai",
      modelId: "model_gpt",
      reasoningLevel: "high",
    });
  });

  it("offers only the selected model's reasoning levels and preserves supported changes", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn(async () => undefined);
    renderSection(onChange, {
      enabled: true,
      providerId: "provider_openai",
      modelId: "model_gpt",
      reasoningLevel: null,
    });

    await user.click(screen.getByRole("combobox", { name: "子智能体思考等级" }));
    expect(screen.getByRole("option", { name: /高 \(high\)/ })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: /低 \(low\)/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: /高 \(high\)/ }));
    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith({
        enabled: true,
        providerId: "provider_openai",
        modelId: "model_gpt",
        reasoningLevel: "high",
      }),
    );
  });

  it("shows the parent reasoning level as read-only while following the parent model", () => {
    renderSection();
    expect(screen.getByText("沿用父任务的思考等级")).toBeVisible();
    expect(screen.queryByRole("combobox", { name: "子智能体思考等级" })).not.toBeInTheDocument();
  });

  /** 保存失败必须反馈给用户并解除提交锁，同时保留原来的权威开关值。 */
  it("reports a rejected save and permits retry without changing the authoritative value", async () => {
    const user = userEvent.setup();
    const feedback = vi.spyOn(toast, "error").mockReturnValue("subagent-error");
    const onChange = vi
      .fn()
      .mockRejectedValueOnce(new Error("save failed"))
      .mockResolvedValue(undefined);
    try {
      renderSection(onChange);
      const toggle = screen.getByRole("switch", { name: "启用子智能体" });
      await user.click(toggle);
      await waitFor(() => expect(feedback).toHaveBeenCalledWith("子智能体设置保存失败"));
      expect(toggle).toHaveAttribute("aria-checked", "true");
      expect(toggle).not.toBeDisabled();
      expect(screen.getByRole("status")).toHaveTextContent("子智能体设置保存失败");
      await user.click(screen.getByRole("button", { name: "重试" }));
      await waitFor(() => expect(onChange).toHaveBeenCalledTimes(2));
    } finally {
      feedback.mockRestore();
    }
  });

  it("keeps follow-parent available and guides to model settings when the catalog is empty", async () => {
    const user = userEvent.setup();
    const onOpenModels = vi.fn();
    renderSection(
      vi.fn(async () => undefined),
      undefined,
      [],
      onOpenModels,
    );
    expect(screen.getByRole("combobox", { name: "子智能体模型" })).toHaveTextContent("跟随父任务");
    await user.click(screen.getByRole("button", { name: "前往模型设置" }));
    expect(onOpenModels).toHaveBeenCalledTimes(1);
  });

  it("guides to model settings when providers exist but contain no models", () => {
    renderSection(
      vi.fn(async () => undefined),
      undefined,
      PROVIDERS.map((provider) => ({ ...provider, models: [] })),
    );

    expect(screen.getByText("尚未配置可用模型。")).toBeVisible();
    expect(screen.getByRole("button", { name: "前往模型设置" })).toBeVisible();
  });
});
