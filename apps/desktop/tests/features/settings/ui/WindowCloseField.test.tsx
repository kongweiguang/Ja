// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WindowCloseField } from "@/features/settings/ui/WindowCloseField";
import type { SettingsDesktopPort } from "@/features/settings/application/ports";

afterEach(cleanup);

/** 未使用的原生能力仍显式提供，使关闭策略测试不依赖网络或真正退出应用。 */
function desktop(overrides: Partial<SettingsDesktopPort>): SettingsDesktopPort {
  return {
    readCloseBehavior: vi.fn(async () => "background" as const),
    saveCloseBehavior: vi.fn(async () => undefined),
    openExternalUrl: vi.fn(async () => undefined),
    checkForUpdate: vi.fn(async () => ({ kind: "up-to-date" as const })),
    installUpdate: vi.fn(async () => undefined),
    relaunchAfterUpdate: vi.fn(async () => undefined),
    ...overrides,
  };
}

describe("WindowCloseField", () => {
  /** 保存失败不能把菜单选择伪装成原生已生效；重新选择必须能够恢复。 */
  it("retains the authoritative value on save failure and allows retry", async () => {
    const user = userEvent.setup();
    const saveCloseBehavior = vi
      .fn()
      .mockRejectedValueOnce(new Error("disk full"))
      .mockResolvedValueOnce(undefined);
    render(<WindowCloseField desktop={desktop({ saveCloseBehavior })} />);
    const control = screen.getByRole("combobox", { name: "关闭窗口时" });
    await waitFor(() => expect(control.textContent).toContain("留在后台"));
    await user.click(control);
    await user.click(screen.getByRole("option", { name: "退出 Ja" }));
    expect((await screen.findByRole("alert")).textContent).toContain("仍使用原来的关闭方式");
    expect(control.textContent).toContain("留在后台");
    await user.click(control);
    await user.click(screen.getByRole("option", { name: "退出 Ja" }));
    await waitFor(() => expect(control.textContent).toContain("退出 Ja"));
    expect(saveCloseBehavior.mock.calls).toEqual([["exit"], ["exit"]]);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  /** 读取失败要有真实重试入口，不能以默认后台值掩盖未知原生配置。 */
  it("recovers an initial read failure without saving a guessed default", async () => {
    const user = userEvent.setup();
    const readCloseBehavior = vi
      .fn()
      .mockRejectedValueOnce(new Error("unavailable"))
      .mockResolvedValueOnce("exit");
    const port = desktop({ readCloseBehavior });
    render(<WindowCloseField desktop={port} />);
    await screen.findByRole("alert");
    expect(screen.getByRole("combobox", { name: "关闭窗口时" }).hasAttribute("disabled")).toBe(
      true,
    );
    await user.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() =>
      expect(screen.getByRole("combobox", { name: "关闭窗口时" }).textContent).toContain("退出 Ja"),
    );
    expect(port.saveCloseBehavior).not.toHaveBeenCalled();
  });
});
