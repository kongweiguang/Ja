// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  createCommandRegistry,
  searchCommandActions,
  type CommandDescriptor,
} from "@/features/command/domain/commandRegistry";

describe("Ja command registry", () => {
  it("keeps canonical action metadata and drops duplicate or blank ids", () => {
    const first: CommandDescriptor = {
      id: "open",
      label: "打开项目",
      keywords: ["project", "approval_required"],
    };
    const second: CommandDescriptor = { ...first, label: "重复动作" };
    const blank: CommandDescriptor = { ...first, id: "   " };

    expect(createCommandRegistry([first, second, blank])).toHaveLength(1);
    expect(createCommandRegistry([first])[0]).toMatchObject(first);
  });

  it("ranks labels and explicit keywords while preserving stable ties", () => {
    const actions = createCommandRegistry([
      {
        id: "search",
        label: "搜索工作区",
        keywords: ["find", "approval_required"],
      },
      {
        id: "settings",
        label: "设置",
        keywords: ["workspace preferences"],
      },
      {
        id: "project",
        label: "选择项目",
        keywords: ["approval_required"],
      },
    ]);

    expect(searchCommandActions(actions, "approval_required").map((action) => action.id)).toEqual([
      "search",
      "project",
    ]);
    expect(searchCommandActions(actions, "find").map((action) => action.id)).toEqual(["search"]);
    expect(searchCommandActions(actions, "不存在")).toEqual([]);
  });
});
