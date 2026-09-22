// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SkillsSection } from "@/features/settings/ui/skills";
import type { SkillProjection } from "@/features/settings/domain/types";

const skill = (overrides: Partial<SkillProjection> = {}): SkillProjection => ({
  id: "user:review",
  name: "review",
  source: "user",
  description: "检查变更、风险与测试覆盖。",
  enabled: false,
  status: "disabled",
  ...overrides,
});

describe("SkillsSection", () => {
  afterEach(() => {
    cleanup();
  });

  it("keeps the global view compact and hides the project tab without a trusted project", () => {
    render(
      <SkillsSection
        globalSkills={[skill()]}
        projectAvailable={false}
        onToggleSkill={vi.fn(async () => undefined)}
      />,
    );

    expect(screen.getByRole("tab", { name: "全局" })).toBeDefined();
    expect(screen.queryByRole("tab", { name: "当前项目" })).toBeNull();
    expect(screen.getByText("检查变更、风险与测试覆盖。")).toBeDefined();
  });

  it("switches to the trusted project view and keeps source labels visible", async () => {
    const user = userEvent.setup();
    render(
      <SkillsSection
        globalSkills={[skill()]}
        projectSkills={[skill({ id: "project:review", source: "project", enabled: true })]}
        projectAvailable
        onToggleSkill={vi.fn(async () => undefined)}
      />,
    );

    await user.click(screen.getByRole("tab", { name: "当前项目" }));
    expect(screen.getByLabelText("当前项目 Skills")).toBeDefined();
    expect(screen.getByText("项目")).toBeDefined();
  });

  it("targets the named Skill switch and keeps mutation failures recoverable", async () => {
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

    const toggle = screen.getByRole("switch", { name: /review/u });
    await user.click(toggle);
    await waitFor(() => expect(onToggleSkill).toHaveBeenCalledWith("user:review", true, "user"));
    expect(screen.getByRole("status").textContent).toContain("Skill 状态修改失败");
    expect(toggle.hasAttribute("disabled")).toBe(false);
  });

  /** 保存直到权威回读完成前锁住整个子面，不能从全局页误切换到项目页。 */
  it("locks scope switching and other Skill actions while a write is pending", async () => {
    const user = userEvent.setup();
    let release: (() => void) | undefined;
    const onToggleSkill = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
    );
    render(
      <SkillsSection
        globalSkills={[skill()]}
        projectSkills={[skill({ id: "project:review", source: "project" })]}
        projectAvailable
        onToggleSkill={onToggleSkill}
      />,
    );

    const globalTab = screen.getByRole("tab", { name: "全局" });
    const projectTab = screen.getByRole("tab", { name: "当前项目" });
    const toggle = screen.getByRole("switch", { name: /review/u });
    await user.click(toggle);

    await waitFor(() => expect(onToggleSkill).toHaveBeenCalledWith("user:review", true, "user"));
    expect(globalTab).toBeDisabled();
    expect(projectTab).toBeDisabled();
    expect(toggle).toBeDisabled();

    release?.();
    await waitFor(() => expect(globalTab).not.toBeDisabled());
    expect(projectTab).not.toBeDisabled();
    expect(toggle).not.toBeDisabled();
  });

  /** 缺失记录在窄屏会显示为图标；ARIA 名称仍保留对象和动作，避免键盘操作失义。 */
  it("keeps the missing-record action identifiable when its compact label is hidden", async () => {
    const user = userEvent.setup();
    const onToggleSkill = vi.fn(async () => undefined);
    render(
      <SkillsSection
        globalSkills={[skill({ missing: true, status: "error", error: "文件已移除" })]}
        projectAvailable={false}
        onToggleSkill={onToggleSkill}
      />,
    );

    const remove = screen.getByRole("button", { name: "移除 review 的记录" });
    expect(remove).toHaveAttribute("title", "移除记录");
    await user.click(remove);
    await waitFor(() => expect(onToggleSkill).toHaveBeenCalledWith("user:review", false, "user"));
  });
});
