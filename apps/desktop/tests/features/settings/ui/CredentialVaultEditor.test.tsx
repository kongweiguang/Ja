// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
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

  it("keeps an unknown vault state neutral and still allows explicit deletion", async () => {
    const user = userEvent.setup();
    const onClearCredential = vi.fn(async () => undefined);
    render(
      <CredentialVaultEditor
        reference="cred_mcp_existing"
        configured={undefined}
        onReplaceCredential={vi.fn(async () => undefined)}
        onClearCredential={onClearCredential}
      />,
    );

    expect(screen.getByText("密钥不会回显")).toBeInTheDocument();
    expect(screen.queryByText("未配置")).toBeNull();
    const deleteButton = screen.getByRole("button", { name: "删除凭据" });
    expect(deleteButton).toBeEnabled();
    await user.click(deleteButton);
    await user.click(screen.getByRole("button", { name: "确认删除" }));

    await waitFor(() => expect(onClearCredential).toHaveBeenCalledWith("cred_mcp_existing"));
    expect(screen.queryByRole("dialog", { name: "删除系统凭据" })).toBeNull();
    expect(deleteButton).toHaveFocus();
  });

  /** 删除失败必须保留确认框和可重试路径，避免用户误以为 Secret 已被清除。 */
  it("keeps the delete confirmation open when clearing the credential fails", async () => {
    const user = userEvent.setup();
    const onClearCredential = vi.fn(async () => {
      throw new Error("vault unavailable");
    });
    render(
      <CredentialVaultEditor
        reference="cred_mcp_existing"
        configured={true}
        onReplaceCredential={vi.fn(async () => undefined)}
        onClearCredential={onClearCredential}
      />,
    );

    await user.click(screen.getByRole("button", { name: "删除凭据" }));
    const dialog = screen.getByRole("dialog", { name: "删除系统凭据" });
    await user.click(within(dialog).getByRole("button", { name: "确认删除" }));

    await waitFor(() => expect(onClearCredential).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("dialog", { name: "删除系统凭据" })).toBeInTheDocument();
    expect(
      within(screen.getByRole("dialog", { name: "删除系统凭据" })).getByRole("alert"),
    ).toHaveTextContent("删除失败，请确认该凭据存在并重试。");
  });
});
