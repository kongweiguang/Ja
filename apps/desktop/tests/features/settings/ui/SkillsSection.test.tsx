// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SkillsSection } from "@/features/settings/ui/skills";
import type { SkillProjection } from "@/features/settings/domain/types";

const skill = (overrides: Partial<SkillProjection> = {}): SkillProjection => ({
  id: "user:review",
  name: "review",
  source: "user",
  description: "检查变更、风险与测试覆盖。",
  enabled: true,
  status: "ready",
  ...overrides,
});

describe("SkillsSection", () => {
  afterEach(cleanup);

  it("keeps global and project groups visible together", () => {
    render(
      <SkillsSection
        globalSkills={[skill()]}
        projectSkills={[skill({ id: "project:review", source: "project" })]}
        projectAvailable
        onToggleSkill={vi.fn(async () => undefined)}
      />,
    );
    expect(
      within(screen.getByRole("region", { name: "全局 Skills" })).getByRole("switch"),
    ).toBeChecked();
    expect(
      within(screen.getByRole("region", { name: "项目 Skills" })).getByRole("switch"),
    ).toBeChecked();
    expect(screen.getByText(/项目.*同名 Skill 覆盖/u)).toBeInTheDocument();
  });

  it("routes each switch to its own persisted scope", async () => {
    const user = userEvent.setup();
    const onToggleSkill = vi.fn(async () => undefined);
    render(
      <SkillsSection
        globalSkills={[skill()]}
        projectSkills={[skill({ id: "project:review", source: "project" })]}
        projectAvailable
        onToggleSkill={onToggleSkill}
      />,
    );
    await user.click(
      within(screen.getByRole("region", { name: "全局 Skills" })).getByRole("switch"),
    );
    await user.click(
      within(screen.getByRole("region", { name: "项目 Skills" })).getByRole("switch"),
    );
    expect(onToggleSkill).toHaveBeenNthCalledWith(1, "user:review", false, "user");
    expect(onToggleSkill).toHaveBeenNthCalledWith(2, "project:review", false, "project");
  });

  it("preserves the switch after a failed save", async () => {
    const user = userEvent.setup();
    const onToggleSkill = vi.fn(async () => {
      throw new Error("network unavailable");
    });
    render(
      <SkillsSection
        globalSkills={[skill()]}
        projectAvailable={false}
        onToggleSkill={onToggleSkill}
      />,
    );
    const toggle = within(screen.getByRole("region", { name: "全局 Skills" })).getByRole("switch");
    await user.click(toggle);
    expect(toggle).toBeChecked();
    expect(toggle).not.toBeDisabled();
    expect(screen.getByText("Skill 状态修改失败。")).toBeInTheDocument();
  });

  it("locks both groups and reports busy while a save is pending", async () => {
    const user = userEvent.setup();
    let release: (() => void) | undefined;
    const onToggleSkill = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
    );
    const onBusyChange = vi.fn();
    render(
      <SkillsSection
        globalSkills={[skill()]}
        projectSkills={[skill({ id: "project:review", source: "project" })]}
        projectAvailable
        onToggleSkill={onToggleSkill}
        onBusyChange={onBusyChange}
      />,
    );
    await user.click(
      within(screen.getByRole("region", { name: "全局 Skills" })).getByRole("switch"),
    );
    await waitFor(() => expect(onBusyChange).toHaveBeenCalledWith(true));
    expect(
      within(screen.getByRole("region", { name: "项目 Skills" })).getByRole("switch"),
    ).toBeDisabled();
    release?.();
    await waitFor(() => expect(onBusyChange).toHaveBeenCalledWith(false));
  });

  it("removes a missing disabled record through its original scope", async () => {
    const user = userEvent.setup();
    const onToggleSkill = vi.fn(async () => undefined);
    render(
      <SkillsSection
        globalSkills={[skill({ enabled: false, missing: true, status: "error" })]}
        projectAvailable={false}
        onToggleSkill={onToggleSkill}
      />,
    );
    await user.click(screen.getByRole("button", { name: "移除 review 的停用记录" }));
    expect(onToggleSkill).toHaveBeenCalledWith("user:review", true, "user");
  });
});
