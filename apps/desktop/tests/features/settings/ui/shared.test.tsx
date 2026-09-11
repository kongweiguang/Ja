// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { settingsMutationErrorMessage } from "@/features/settings/ui/shared";

describe("settingsMutationErrorMessage", () => {
  it("explains how to resolve a model referenced by the subagent policy", () => {
    expect(
      settingsMutationErrorMessage(
        new Error("subagent model replacement is required"),
        "模型删除失败",
      ),
    ).toBe("该模型正用于子智能体，请先在“子智能体”设置中更改模型。");
  });

  it("keeps the generic fallback for unrelated mutation failures", () => {
    expect(settingsMutationErrorMessage(new Error("other"), "模型删除失败")).toBe("模型删除失败");
  });
});
