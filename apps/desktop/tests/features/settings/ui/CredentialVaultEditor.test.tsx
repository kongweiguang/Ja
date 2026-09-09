// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CredentialVaultEditor } from "@/features/settings/ui/CredentialVaultEditor";

afterEach(cleanup);

describe("CredentialVaultEditor", () => {
  it.each([
    ["revision_conflict", "配置版本仍有冲突，请重新加载设置后再试。"],
    ["storage_unavailable", "系统凭据库暂时不可用，请稍后重试。"],
    ["invalid_input", "密钥或凭据引用无效，请检查后重试。"],
    ["invalid_response", "凭据配置异常，请先恢复设置后再保存。"],
  ] as const)("shows actionable %s feedback and clears the secret field", async (code, message) => {
    const user = userEvent.setup();
    const onReplaceCredential = vi.fn(async () => {
      throw Object.assign(new Error("redacted"), { code });
    });
    render(
      <CredentialVaultEditor
        reference="cred_deepseek"
        configured={false}
        onReplaceCredential={onReplaceCredential}
        onClearCredential={vi.fn(async () => undefined)}
      />,
    );
    const input = screen.getByLabelText("API key / token");

    await user.type(input, "test-secret");
    await user.click(screen.getByRole("button", { name: "保存或替换密钥" }));

    expect(await screen.findByText(message)).not.toBeNull();
    expect((input as HTMLInputElement).value).toBe("");
  });
});
