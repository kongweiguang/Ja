// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AboutSection, SettingsUpdateAction } from "@/features/settings/ui/about";
import type { AppUpdaterController } from "@/features/settings/application/useAppUpdater";

const openExternalUrl = vi.fn(() => Promise.resolve());

/** 为展示层测试构造窄 controller，动作调用保持可观察且不复制 hook 状态机。 */
function updater(
  state: AppUpdaterController["state"],
  overrides: Partial<AppUpdaterController> = {},
): AppUpdaterController {
  return {
    state,
    check: vi.fn(() => Promise.resolve()),
    install: vi.fn(() => Promise.resolve()),
    relaunch: vi.fn(() => Promise.resolve()),
    ...overrides,
  };
}

describe("AboutSection", () => {
  it("shows verified Ja facts and opens the canonical GitHub repository", async () => {
    render(
      <AboutSection updater={updater({ kind: "up-to-date" })} openExternalUrl={openExternalUrl} />,
    );

    expect(screen.getByRole("heading", { name: "关于" })).toBeDefined();
    expect(screen.getByRole("heading", { name: "Ja" })).toBeDefined();
    expect(screen.getByText("GPL-3.0-or-later")).toBeDefined();
    expect(screen.getByText("github.com/kongweiguang/Ja")).toBeDefined();

    fireEvent.click(screen.getByRole("button", { name: "打开" }));
    expect(openExternalUrl).toHaveBeenCalledWith("https://github.com/kongweiguang/Ja");
  });

  it("keeps the settings toolbar quiet until an update is available", () => {
    const latest = updater({ kind: "up-to-date" });
    const { rerender } = render(<SettingsUpdateAction updater={latest} />);
    expect(screen.queryByRole("button", { name: /更新到/u })).toBeNull();

    const available = updater({
      kind: "available",
      currentVersion: "0.1.0",
      version: "0.2.0",
    });
    rerender(<SettingsUpdateAction updater={available} />);
    fireEvent.click(screen.getByRole("button", { name: "更新到 v0.2.0" }));
    expect(available.install).toHaveBeenCalledOnce();
  });

  it("shows bounded progress and a recoverable restart action", () => {
    const installing = updater({
      kind: "installing",
      currentVersion: "0.1.0",
      version: "0.2.0",
      percent: 48,
    });
    const { rerender } = render(
      <AboutSection updater={installing} openExternalUrl={openExternalUrl} />,
    );
    expect(screen.getByText("正在下载 v0.2.0 · 48%")).toBeDefined();
    expect(screen.getByRole("button", { name: "48%" }).hasAttribute("disabled")).toBe(true);

    const restart = updater({ kind: "restart-required", version: "0.2.0" });
    rerender(<AboutSection updater={restart} openExternalUrl={openExternalUrl} />);
    fireEvent.click(screen.getByRole("button", { name: "重新启动" }));
    expect(restart.relaunch).toHaveBeenCalledOnce();
  });
});
