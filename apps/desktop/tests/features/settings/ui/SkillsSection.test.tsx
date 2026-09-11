// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { SkillsSection } from "@/features/settings/ui/skills";
import type { SkillProjection } from "@/features/settings/domain/types";

const longDescription = "这是一个没有空格的超长 Skill 描述，用来验证真实布局溢出时才出现展开入口。";
let layoutOverflows = false;
let notifyResize: (() => void) | undefined;
const originalScrollHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "scrollHeight");
const originalClientHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "clientHeight");

class MockResizeObserver {
  constructor(callback: () => void) {
    notifyResize = callback;
  }

  observe(): void {}

  disconnect(): void {}
}

const skill = (overrides: Partial<SkillProjection> = {}): SkillProjection => ({
  id: "skill_review",
  name: "review",
  source: "user",
  description: longDescription,
  enabled: false,
  status: "disabled",
  ...overrides,
});

describe("SkillsSection", () => {
  beforeAll(() => {
    Object.defineProperty(HTMLElement.prototype, "scrollHeight", {
      configurable: true,
      get() {
        return this.classList.contains("ja-skill-description") && layoutOverflows ? 80 : 16;
      },
    });
    Object.defineProperty(HTMLElement.prototype, "clientHeight", {
      configurable: true,
      get() {
        if (!this.classList.contains("ja-skill-description")) return 0;
        return layoutOverflows && !this.classList.contains("is-expanded") ? 16 : 80;
      },
    });
  });

  beforeEach(() => {
    layoutOverflows = false;
    notifyResize = undefined;
    vi.stubGlobal("ResizeObserver", MockResizeObserver);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  afterAll(() => {
    if (originalScrollHeight === undefined) {
      delete (HTMLElement.prototype as { scrollHeight?: number }).scrollHeight;
    } else Object.defineProperty(HTMLElement.prototype, "scrollHeight", originalScrollHeight);
    if (originalClientHeight === undefined) {
      delete (HTMLElement.prototype as { clientHeight?: number }).clientHeight;
    } else Object.defineProperty(HTMLElement.prototype, "clientHeight", originalClientHeight);
  });

  it("keeps four source groups compact and does not offer a meaningless action for short text", () => {
    render(
      <SkillsSection
        skills={[skill({ description: "短描述" })]}
        onToggleSkill={vi.fn(async () => undefined)}
      />,
    );

    for (const heading of ["内置", "用户", "Ja", "项目"]) {
      expect(screen.getByRole("heading", { name: heading })).toBeDefined();
    }
    expect(screen.getAllByText("暂无 Skills")).toHaveLength(3);
    expect(screen.queryByRole("button", { name: "查看完整描述" })).toBeNull();
  });

  it("shows disclosure only after geometry reports overflow and supports expand/collapse", async () => {
    const user = userEvent.setup();
    render(<SkillsSection skills={[skill()]} onToggleSkill={vi.fn(async () => undefined)} />);
    expect(screen.queryByRole("button", { name: "查看完整描述" })).toBeNull();

    layoutOverflows = true;
    act(() => notifyResize?.());
    const expand = await screen.findByRole("button", { name: "查看完整描述" });
    await user.click(expand);
    expect(screen.getByRole("button", { name: "收起描述" })).toBeDefined();
    expect(screen.getByText(longDescription).className).toContain("is-expanded");

    await user.click(screen.getByRole("button", { name: "收起描述" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "查看完整描述" })).toBeDefined());
  });

  it("targets the named Skill switch and keeps mutation failures recoverable", async () => {
    const user = userEvent.setup();
    const onToggleSkill = vi.fn(async () => {
      throw new Error("network unavailable");
    });
    render(<SkillsSection skills={[skill()]} onToggleSkill={onToggleSkill} />);

    const toggle = screen.getByRole("switch", { name: /review/u });
    await user.click(toggle);
    await waitFor(() => expect(onToggleSkill).toHaveBeenCalledWith("skill_review", true));
    expect(screen.getByRole("status").textContent).toContain("Skill 状态修改失败");
    expect(toggle.hasAttribute("disabled")).toBe(false);
  });
});
