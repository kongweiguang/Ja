// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ExecutionScopeDetails } from "@/features/settings/ui/ExecutionScopeDetails";

afterEach(cleanup);

describe("ExecutionScopeDetails", () => {
  /** 说明组件只显示传入快照的来源，不增加编辑控件或第二份权限状态。 */
  it("renders compact source details for a ready project thread", () => {
    render(
      <ExecutionScopeDetails
        scope={{
          scopedDefault: "approval_required",
          projectOverride: true,
          scopeReady: true,
          workspaceKind: "project",
          threadAccessMode: "approval_required",
        }}
      />,
    );

    const note = screen.getByRole("note", { name: "执行确认生效范围" });
    expect(note.textContent).toContain(
      "新会话采用默认值；已有会话保留选择，执行时以更严格的权限为准",
    );
    expect(note.textContent).toContain("当前项目：需要确认（项目限制）");
    expect(note.textContent).toContain("当前会话：需要确认（会话选择）");
    expect(note.querySelectorAll("input,button,select")).toHaveLength(0);
  });

  /** 未完成同步时不把旧 project snapshot 作为当前项目事实显示。 */
  it("shows synchronization state instead of a stale project value", () => {
    render(
      <ExecutionScopeDetails
        scope={{
          scopedDefault: "full_access",
          projectOverride: true,
          scopeReady: false,
          workspaceKind: "project",
          threadAccessMode: "full_access",
        }}
      />,
    );

    const note = screen.getByRole("note", { name: "执行确认生效范围" });
    expect(note.textContent).toContain("正在同步当前项目设置");
    expect(note.textContent).not.toContain("当前项目：全部执行");
    expect(note.textContent).not.toContain("当前会话：");
  });
});
