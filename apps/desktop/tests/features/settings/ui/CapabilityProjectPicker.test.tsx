// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CapabilityProjectPicker } from "@/features/settings/ui/CapabilityProjectPicker";
import type { WorkspaceProjection } from "@/features/workspace";

const projects: WorkspaceProjection[] = [
  {
    kind: "project",
    workspaceId: "ws_alpha",
    displayName: "同名项目",
    rootPath: "C:\\work\\alpha",
    trust: "trusted",
    legacySharedWorkspaceId: null,
  },
  {
    kind: "project",
    workspaceId: "ws_beta",
    displayName: "同名项目",
    rootPath: "C:\\work\\beta",
    trust: "trusted",
    legacySharedWorkspaceId: null,
  },
];

describe("CapabilityProjectPicker", () => {
  afterEach(cleanup);

  it("searches registered paths and emits only the selected workspace id", async () => {
    const user = userEvent.setup();
    const onSelectProject = vi.fn();
    render(
      <CapabilityProjectPicker
        projects={projects}
        selectedProjectId="ws_alpha"
        onSelectProject={onSelectProject}
      />,
    );

    await user.click(screen.getByRole("button", { name: /选择设置项目/u }));
    await user.type(screen.getByRole("textbox", { name: "搜索已有项目" }), "beta");
    expect(screen.getByRole("option", { name: /beta/u })).toHaveAttribute(
      "title",
      projects[1]?.rootPath,
    );
    await user.click(screen.getByRole("option", { name: /beta/u }));
    expect(onSelectProject).toHaveBeenCalledWith("ws_beta");
  });

  it("keeps the selected project visible when the catalog temporarily lacks that row", () => {
    render(
      <CapabilityProjectPicker
        projects={[]}
        selectedProjectId="ws_missing"
        onSelectProject={vi.fn()}
      />,
    );
    expect(screen.getByRole("button", { name: "选择设置项目：项目不可用" })).toBeInTheDocument();
  });

  it("moves focus from search to the filtered option with the keyboard", async () => {
    const user = userEvent.setup();
    const onSelectProject = vi.fn();
    render(
      <CapabilityProjectPicker
        projects={projects}
        selectedProjectId="ws_alpha"
        onSelectProject={onSelectProject}
      />,
    );
    await user.click(screen.getByRole("button", { name: /选择设置项目/u }));
    await user.type(screen.getByRole("textbox", { name: "搜索已有项目" }), "beta");
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("option", { name: /beta/u })).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(onSelectProject).toHaveBeenCalledWith("ws_beta");
  });
});
