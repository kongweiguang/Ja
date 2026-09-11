// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { describeExecutionScope } from "@/features/settings/domain/executionScope";

describe("describeExecutionScope", () => {
  /** 项目、会话均已同步时分别呈现真实来源，避免把会话选择写成运行时权限。 */
  it("separates the project restriction from the thread selection", () => {
    expect(
      describeExecutionScope({
        scopedDefault: "approval_required",
        projectOverride: true,
        scopeReady: true,
        workspaceKind: "project",
        threadAccessMode: "full_access",
      }),
    ).toEqual([
      "新会话采用默认值；已有会话保留选择，执行时以更严格的权限为准。",
      "当前项目：需要确认（项目限制） · 当前会话：全部执行（会话选择）",
    ]);
  });

  /** Workspace 切换期间必须隐藏缓存中的旧项目权限与旧线程选择。 */
  it("does not expose stale project facts while the scope is synchronizing", () => {
    expect(
      describeExecutionScope({
        scopedDefault: "full_access",
        projectOverride: true,
        scopeReady: false,
        workspaceKind: "project",
        threadAccessMode: "full_access",
      }),
    ).toEqual(["正在同步当前项目设置…"]);
  });

  /** 无项目时只显示全局默认与会话语义，不伪造项目来源。 */
  it("keeps the general scope free of project claims", () => {
    expect(
      describeExecutionScope({
        scopedDefault: "full_access",
        projectOverride: false,
        scopeReady: true,
        workspaceKind: "general",
      }),
    ).toEqual(["新会话采用默认值；已有会话保留选择，执行时以更严格的权限为准。"]);
  });

  /** 普通无项目对话仍可显示真实 Thread 选择，但不引入项目来源。 */
  it("shows a synchronized general thread selection without inventing a project source", () => {
    expect(
      describeExecutionScope({
        scopedDefault: "full_access",
        projectOverride: false,
        scopeReady: true,
        workspaceKind: "general",
        threadAccessMode: "approval_required",
      }),
    ).toEqual([
      "新会话采用默认值；已有会话保留选择，执行时以更严格的权限为准。",
      "当前会话：需要确认（会话选择）",
    ]);
  });
});
